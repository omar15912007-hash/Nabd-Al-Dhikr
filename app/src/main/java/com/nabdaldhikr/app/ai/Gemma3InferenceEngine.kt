package com.nabdaldhikr.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface GemmaTextGenerator : AutoCloseable {
    val isModelAvailable: Boolean
    suspend fun formulateGroundedAnswer(userQuery: String, verifiedContext: String): String?
    fun releaseMemory()
}

/**
 * On-device Gemma 3 (270M) Inference Engine powered by MediaPipe GenAI (`tasks-genai`).
 *
 * Memory Discipline:
 *  - Lazily loads `gemma3-270m-nabd.task` (~400MB active inference RAM) ONLY when invoked.
 *  - Immediately releases native memory via `releaseMemory()` when the user closes `NabdAiChatScreen`.
 *  - Strictly grounded in verified RAG / Dorar.net passages (never invents Hadiths or issues fatwas).
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
        verifiedContext: String
    ): String? = withContext(Dispatchers.Default) {
        if (!isModelAvailable) return@withContext null

        val inference = getOrCreateInference() ?: return@withContext null
        val prompt = buildGemma3GroundedPrompt(userQuery, verifiedContext)

        return@withContext try {
            val raw = inference.generateResponse(prompt).trim()
            if (raw.length < 10) null else raw
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
                    .setMaxTokens(512)
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
     * Releases the ~400MB native inference memory immediately when leaving chat.
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

    private fun buildGemma3GroundedPrompt(userQuery: String, verifiedContext: String): String {
        return """<start_of_turn>user
أنت مساعد «نبض AI» للأذكار النبوية الصحيحة فقط.
قواعد صارمة:
1. اعتمد حصراً على النص الموثّق التالي ولا تخترع أي حديث أو آية.
2. لا تُصدر أي فتوى فقهية أو حكم حلال وحرام أبداً.
3. أجب بالعربية الفصحى الدافئة والميسّرة في 3 إلى 4 أسطر فقط.

النص الموثّق:
$verifiedContext

سؤال المستخدم:
$userQuery
<end_of_turn>
<start_of_turn>model
"""
    }
}
