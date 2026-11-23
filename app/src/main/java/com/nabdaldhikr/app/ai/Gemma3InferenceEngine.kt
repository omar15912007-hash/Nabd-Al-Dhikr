package com.nabdaldhikr.app.ai

import android.content.Context
import android.content.SharedPreferences
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

interface GemmaTextGenerator : AutoCloseable {
    val isModelAvailable: Boolean
    suspend fun formulateGroundedAnswer(
        userQuery: String,
        verifiedContext: String,
        recentTurns: List<Pair<String, String>> = emptyList()
    ): String?
    fun releaseMemory()
}

enum class TaskBundleStatus {
    VALID_MULTI_PREFILL,
    MISSING_OR_TOO_SMALL,
    INVALID_ZIP_OR_ENTRIES,
    LEGACY_SINGLE_PREFILL_256
}

/**
 * Pure JVM-testable pre-flight inspector for `.task` bundles.
 * Rejects truncated downloads or legacy single-`prefill_256` bundles before
 * native MediaPipe JNI runs, preventing fatal C++ `RET_CHECK` aborts (`SIGABRT`).
 */
object TaskBundleInspector {
    private const val MIN_VALID_TASK_BYTES = 1_000_000L
    private const val HEADER_SCAN_BYTES = 512 * 1024
    private val REQUIRED_ENTRIES = setOf("TF_LITE_PREFILL_DECODE", "TOKENIZER_MODEL", "METADATA")

    fun inspect(file: File, minBytes: Long = MIN_VALID_TASK_BYTES): TaskBundleStatus {
        if (!file.isFile || file.length() <= minBytes) {
            return TaskBundleStatus.MISSING_OR_TOO_SMALL
        }

        return try {
            ZipFile(file).use { zip ->
                val hasAllEntries = REQUIRED_ENTRIES.all { zip.getEntry(it) != null }
                if (!hasAllEntries) return TaskBundleStatus.INVALID_ZIP_OR_ENTRIES

                val tfliteEntry = zip.getEntry("TF_LITE_PREFILL_DECODE")
                    ?: return TaskBundleStatus.INVALID_ZIP_OR_ENTRIES
                val headerText = zip.getInputStream(tfliteEntry).use { input ->
                    val buffer = ByteArray(HEADER_SCAN_BYTES)
                    var totalRead = 0
                    while (totalRead < buffer.size) {
                        val read = input.read(buffer, totalRead, buffer.size - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                    String(buffer, 0, totalRead, Charsets.ISO_8859_1)
                }

                // Multi-signature bundles must provide larger prefill buckets (512 or 1024)
                // to prevent MediaPipe `prefill_runner_set.empty()` native aborts.
                val hasLargePrefill = headerText.contains("prefill_512") || headerText.contains("prefill_1024")
                val hasLegacy256Only = headerText.contains("prefill_256") && !hasLargePrefill
                if (hasLegacy256Only) {
                    return TaskBundleStatus.LEGACY_SINGLE_PREFILL_256
                }

                TaskBundleStatus.VALID_MULTI_PREFILL
            }
        } catch (_: Throwable) {
            TaskBundleStatus.INVALID_ZIP_OR_ENTRIES
        }
    }
}

/**
 * Synchronous crash circuit-breaker persisted via `SharedPreferences.commit()` (or an
 * in-memory store in JVM unit tests). If a previous attempt aborted inside native C++
 * `libllm_inference_engine_jni.so` (`SIGABRT`), the exact `(size:mtime)` model signature is
 * flagged on next start and bypassed permanently so the UI process never crashes twice.
 */
class NativeCrashCircuitBreaker(private val store: KeyValueStore) {

    interface KeyValueStore {
        fun getBoolean(key: String, default: Boolean): Boolean
        fun getString(key: String, default: String): String
        fun commitValues(
            inFlight: Boolean,
            inFlightSignature: String,
            blockedSignature: String? = null,
            verifiedSignature: String? = null
        )
    }

    companion object {
        private const val KEY_IN_FLIGHT = "gemma_native_in_flight"
        private const val KEY_IN_FLIGHT_SIG = "gemma_native_in_flight_sig"
        private const val KEY_BLOCKED_SIG = "gemma_native_blocked_sig"
        private const val KEY_VERIFIED_SIG = "gemma_native_verified_sig"

        fun fileSignature(file: File): String = "${file.length()}:${file.lastModified()}"

        fun fromSharedPreferences(prefs: SharedPreferences): NativeCrashCircuitBreaker {
            return NativeCrashCircuitBreaker(
                object : KeyValueStore {
                    override fun getBoolean(key: String, default: Boolean): Boolean =
                        prefs.getBoolean(key, default)

                    override fun getString(key: String, default: String): String =
                        prefs.getString(key, default).orEmpty().ifEmpty { default }

                    override fun commitValues(
                        inFlight: Boolean,
                        inFlightSignature: String,
                        blockedSignature: String?,
                        verifiedSignature: String?
                    ) {
                        val editor = prefs.edit()
                            .putBoolean(KEY_IN_FLIGHT, inFlight)
                            .putString(KEY_IN_FLIGHT_SIG, inFlightSignature)
                        if (blockedSignature != null) editor.putString(KEY_BLOCKED_SIG, blockedSignature)
                        if (verifiedSignature != null) editor.putString(KEY_VERIFIED_SIG, verifiedSignature)
                        editor.commit()
                    }
                }
            )
        }
    }

    fun isBlocked(signature: String): Boolean {
        if (store.getString(KEY_BLOCKED_SIG, "") == signature) return true

        val wasInFlight = store.getBoolean(KEY_IN_FLIGHT, false)
        val lastSig = store.getString(KEY_IN_FLIGHT_SIG, "")
        if (wasInFlight && lastSig == signature) {
            store.commitValues(
                inFlight = false,
                inFlightSignature = "",
                blockedSignature = signature
            )
            return true
        }
        return false
    }

    fun markNativeCallStarted(signature: String) {
        store.commitValues(inFlight = true, inFlightSignature = signature)
    }

    fun markNativeCallSucceeded(signature: String) {
        store.commitValues(
            inFlight = false,
            inFlightSignature = signature,
            verifiedSignature = signature
        )
    }

    fun clearInFlightWithoutBlocking(signature: String) {
        store.commitValues(inFlight = false, inFlightSignature = signature)
    }

    fun blockSignature(signature: String) {
        store.commitValues(
            inFlight = false,
            inFlightSignature = "",
            blockedSignature = signature
        )
    }
}

/**
 * On-device Gemma 3 (270M) Inference Engine powered by MediaPipe GenAI (`tasks-genai`).
 *
 * Crash Safety & Memory Discipline:
 *  - Verifies `.task` ZIP entries & multi-prefill signatures via `TaskBundleInspector` before JNI load.
 *  - Sets explicit `LlmInference.Backend.CPU` matching `--transpose_kv_cache=True` + `maxTokens(256)`
 *    to avoid MediaPipe `prefill_runner_set` empty aborts and Mali/Adreno OpenCL GPU crashes.
 *  - Guards native JNI calls with `NativeCrashCircuitBreaker` and catches `Throwable` everywhere.
 *  - Immediately releases native memory via `releaseMemory()` when the user closes `NabdAiChatScreen`.
 */
class Gemma3InferenceEngine(
    private val context: Context,
    private val modelFileProvider: () -> File
) : GemmaTextGenerator {

    private val lock = Any()
    private val crashBreaker: NativeCrashCircuitBreaker by lazy {
        val prefs = context.applicationContext.getSharedPreferences("nabd_gemma_guard", Context.MODE_PRIVATE)
        NativeCrashCircuitBreaker.fromSharedPreferences(prefs)
    }

    @Volatile
    private var llmInference: LlmInference? = null

    @Volatile
    private var loadedFileSignature: String? = null

    override val isModelAvailable: Boolean
        get() {
            val file = modelFileProvider()
            if (TaskBundleInspector.inspect(file) != TaskBundleStatus.VALID_MULTI_PREFILL) {
                return false
            }
            return !crashBreaker.isBlocked(NativeCrashCircuitBreaker.fileSignature(file))
        }

    override suspend fun formulateGroundedAnswer(
        userQuery: String,
        verifiedContext: String,
        recentTurns: List<Pair<String, String>>
    ): String? = withContext(Dispatchers.Default) {
        val file = modelFileProvider()
        if (TaskBundleInspector.inspect(file) != TaskBundleStatus.VALID_MULTI_PREFILL) {
            return@withContext null
        }

        val sig = NativeCrashCircuitBreaker.fileSignature(file)
        if (crashBreaker.isBlocked(sig)) {
            return@withContext null
        }

        val inference = getOrCreateInference(file, sig) ?: return@withContext null
        val prompt = buildGemma3GroundedPrompt(userQuery, verifiedContext, recentTurns)

        crashBreaker.markNativeCallStarted(sig)
        return@withContext try {
            val raw = inference.generateResponse(prompt)
                .replace("<end_of_turn>", "")
                .replace("<eos>", "")
                .trim()
            crashBreaker.markNativeCallSucceeded(sig)
            if (isCoherentArabicOutput(raw)) raw else null
        } catch (t: Throwable) {
            if (t is LinkageError) {
                crashBreaker.blockSignature(sig)
            } else {
                crashBreaker.clearInFlightWithoutBlocking(sig)
            }
            null
        }
    }

    private fun getOrCreateInference(file: File, sig: String): LlmInference? {
        if (loadedFileSignature == sig) {
            llmInference?.let { return it }
        }

        synchronized(lock) {
            if (loadedFileSignature == sig) {
                llmInference?.let { return it }
            }
            if (TaskBundleInspector.inspect(file) != TaskBundleStatus.VALID_MULTI_PREFILL) return null
            if (crashBreaker.isBlocked(sig)) return null

            closeCurrentLocked()
            crashBreaker.markNativeCallStarted(sig)
            return try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(256)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                LlmInference.createFromOptions(context, options).also {
                    llmInference = it
                    loadedFileSignature = sig
                    crashBreaker.markNativeCallSucceeded(sig)
                }
            } catch (t: Throwable) {
                if (t is LinkageError) {
                    crashBreaker.blockSignature(sig)
                } else {
                    crashBreaker.clearInFlightWithoutBlocking(sig)
                }
                null
            }
        }
    }

    /**
     * Releases the ~400MB active native inference memory immediately when leaving chat.
     */
    override fun releaseMemory() {
        synchronized(lock) {
            closeCurrentLocked()
        }
    }

    private fun closeCurrentLocked() {
        try {
            llmInference?.close()
        } catch (_: Throwable) {
        } finally {
            llmInference = null
            loadedFileSignature = null
        }
    }

    override fun close() {
        releaseMemory()
    }

    private fun isCoherentArabicOutput(text: String): Boolean {
        if (text.length < 15) return false
        val arabicChars = text.count { it in '\u0600'..'\u06FF' }
        if (arabicChars < 10) return false
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 8 && words.distinct().size <= 2) return false
        return true
    }

    private fun buildGemma3GroundedPrompt(
        userQuery: String,
        verifiedContext: String,
        recentTurns: List<Pair<String, String>>
    ): String {
        val compactContext = verifiedContext.take(750)
        val historyBlock = if (recentTurns.isNotEmpty()) {
            buildString {
                append("سياق المحادثة السابق:\n")
                recentTurns.takeLast(1).forEach { (u, a) ->
                    append("- المستخدم: ").append(u.take(100)).append('\n')
                    append("- المساعد: ").append(a.take(140)).append('\n')
                }
                append('\n')
            }
        } else {
            ""
        }

        return """<start_of_turn>user
أنت «نبض AI»، مساعد أذكار إسلامي حنون ودقيق.
قواعد صارمة:
1. قدّم مقدمة إنسانية دافئة ومختصرة تناسب مشاعر وسؤال المستخدم في سطر أو سطرين.
2. لا تغيّر نص الذكر أو الحديث الموثّق ولا تخترع حديثاً أو عدد تكرار غير موجود في المرجع.
3. لا تصدر أي فتوى فقهية أبداً.

${historyBlock}المرجع الموثّق:
$compactContext

رسالة المستخدم:
${userQuery.take(220)}
<end_of_turn>
<start_of_turn>model
"""
    }
}
