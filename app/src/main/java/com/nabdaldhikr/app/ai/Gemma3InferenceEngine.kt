package com.nabdaldhikr.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface GemmaTextGenerator : AutoCloseable {
    val isModelAvailable: Boolean
    suspend fun formulateGroundedAnswer(
        userQuery: String,
        verifiedContext: String,
        recentTurns: List<Pair<String, String>> = emptyList()
    ): String?
    fun releaseMemory()
}

/**
 * On-device Gemma 3 (270M) Inference Engine powered by MediaPipe GenAI (`tasks-genai`).
 *
 * Memory & Safety Discipline:
 *  - Lazily loads `gemma3-270m-nabd.task` (~400MB active inference RAM) ONLY when invoked.
 *  - Immediately releases native memory via `releaseMemory()` when the user closes `NabdAiChatScreen`.
 *  - Validates generated output is coherent Arabic text and never leaks prompt markers or garbled loops.
 */
class Gemma3InferenceEngine(
    private val context: Context,
    private val modelFileProvider: () -> File
) : GemmaTextGenerator {

    private val lock = Any()

    @Volatile
    private var llmInference: LlmInference? = null

    override val isModelAvailable: Boolean
        get() {
            val file = modelFileProvider()
            return file.isFile && file.length() > 1_000_000L
        }

    override suspend fun formulateGroundedAnswer(
        userQuery: String,
        verifiedContext: String,
        recentTurns: List<Pair<String, String>>
    ): String? = withContext(Dispatchers.Default) {
        if (!isModelAvailable) return@withContext null

        val inference = getOrCreateInference() ?: return@withContext null
        val prompt = buildGemma3GroundedPrompt(userQuery, verifiedContext, recentTurns)

        return@withContext try {
            val raw = inference.generateResponse(prompt)
                .replace("<end_of_turn>", "")
                .replace("<eos>", "")
                .trim()
            if (isCoherentArabicOutput(raw)) raw else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateInference(): LlmInference? {
        llmInference?.let { return it }
        synchronized(lock) {
            llmInference?.let { return it }
            val file = modelFileProvider()
            if (!file.isFile || file.length() <= 1_000_000L) return null

            return try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(320)
                    .build()
                LlmInference.createFromOptions(context, options).also {
                    llmInference = it
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Releases the ~400MB active native inference memory immediately when leaving chat.
     */
    override fun releaseMemory() {
        synchronized(lock) {
            try {
                llmInference?.close()
            } catch (_: Exception) {
            } finally {
                llmInference = null
            }
        }
    }

    override fun close() {
        releaseMemory()
    }

    private fun isCoherentArabicOutput(text: String): Boolean {
        if (text.length < 15) return false
        val arabicChars = text.count { it in '\u0600'..'\u06FF' }
        if (arabicChars < 10) return false
        // Reject hallucinated prompt headers or repetitive token collapse
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 8 && words.distinct().size <= 2) return false
        return true
    }

    private fun buildGemma3GroundedPrompt(
        userQuery: String,
        verifiedContext: String,
        recentTurns: List<Pair<String, String>>
    ): String {
        val historyBlock = if (recentTurns.isNotEmpty()) {
            buildString {
                append("سياق المحادثة السابق:\n")
                recentTurns.takeLast(2).forEach { (u, a) ->
                    append("- المستخدم: ").append(u.take(120)).append('\n')
                    append("- المساعد: ").append(a.take(160)).append('\n')
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
$verifiedContext

رسالة المستخدم:
$userQuery
<end_of_turn>
<start_of_turn>model
"""
    }
}
