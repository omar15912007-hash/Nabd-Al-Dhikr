package com.nabdaldhikr.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nabdaldhikr.app.ai.*
import com.nabdaldhikr.app.data.model.Azkar
import com.nabdaldhikr.app.data.model.AzkarCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared ViewModel for "Nabd AI" (نبض AI):
 *  - Device Compatibility Checker (min 3.0 GB RAM, 450 MB storage, API 26+, arm64-v8a)
 *  - Free GitHub Releases Gemma 3 (270M) ~240MB optional downloader (OkHttp + SHA-256 + Resume)
 *  - Smart Hybrid Router: Anti-Fatwa Guardrail -> Local Adhkar RAG (Offline) -> Dorar.net API (Online)
 *  - Lifecycle-aware Gemma 3 RAM release (~400MB freed immediately when chat screen closes)
 */
class AiAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()
    private val messageIdCounter = AtomicLong(1L)

    val downloadManager = ModelDownloadManager(application, gson)
    private val connectivityChecker = NetworkConnectivityChecker(application)
    private val dorarClient = DorarApiClient()
    private val gemmaEngine = Gemma3InferenceEngine(application) { downloadManager.modelFile() }
    private val router = NabdAiRouter(
        connectivityProbe = connectivityChecker,
        dorarClient = dorarClient,
        gemmaEngine = gemmaEngine
    )

    private val _capabilityReport = MutableStateFlow(DeviceCapabilityChecker.inspect(application))
    val capabilityReport: StateFlow<DeviceCapabilityReport> = _capabilityReport.asStateFlow()

    val downloadState: StateFlow<GemmaDownloadState> = downloadManager.state

    private val _isGemmaInstalled = MutableStateFlow(downloadManager.isGemmaInstalled())
    val isGemmaInstalled: StateFlow<Boolean> = _isGemmaInstalled.asStateFlow()

    private val _offlineMetadata = MutableStateFlow<Map<String, OfflineDhikrMetadata>>(emptyMap())
    val offlineMetadata: StateFlow<Map<String, OfflineDhikrMetadata>> = _offlineMetadata.asStateFlow()

    private val _chatMessages = MutableStateFlow(
        listOf(
            NabdAiChatMessage(
                id = 0L,
                isUser = false,
                text = "أهلاً بك في «نبض AI» ✨\n" +
                    "اسألني عن أي ذكر مثل «أذكار الصباح» أو حالتك الشعورية مثل «قلقان» أو «نوم» أو «سفر» وسأجيبك فوراً بدون إنترنت، " +
                    "وللتحقق من صحة حديث نادر عبر موسوعة الدرر السنية يُستخدم الاتصال بالإنترنت تلقائياً. (بدون فتاوى فقهية).",
                sourceBadge = "مساعد الأذكار الموثّقة • مجاني بالكامل",
                sourceType = NabdAiSourceType.LOCAL_ADHKAR
            )
        )
    )
    val chatMessages: StateFlow<List<NabdAiChatMessage>> = _chatMessages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _sheetAnswer = MutableStateFlow<String?>(null)
    val sheetAnswer: StateFlow<String?> = _sheetAnswer.asStateFlow()

    private val _sheetSourceBadge = MutableStateFlow<String?>(null)
    val sheetSourceBadge: StateFlow<String?> = _sheetSourceBadge.asStateFlow()

    private val _sheetAnswerLatencyMs = MutableStateFlow(0L)
    val sheetAnswerLatencyMs: StateFlow<Long> = _sheetAnswerLatencyMs.asStateFlow()

    private var downloadJob: Job? = null
    private var queryJob: Job? = null

    init {
        viewModelScope.launch {
            loadBundledMetadata()
            refreshCapability()
            _isGemmaInstalled.value = downloadManager.isGemmaInstalled()
        }
    }

    fun refreshCapability() {
        _capabilityReport.value = DeviceCapabilityChecker.inspect(getApplication())
        _isGemmaInstalled.value = downloadManager.isGemmaInstalled()
    }

    fun isOnlineNow(): Boolean = connectivityChecker.isOnline()

    fun clearSheetAnswer() {
        _sheetAnswer.value = null
        _sheetSourceBadge.value = null
        _sheetAnswerLatencyMs.value = 0L
    }

    fun clearChatHistory() {
        _chatMessages.value = listOf(_chatMessages.value.first())
    }

    fun startGemmaDownload() {
        val liveCheck = DeviceCapabilityChecker.inspect(getApplication())
        _capabilityReport.value = liveCheck
        if (!liveCheck.canDownload) {
            downloadJob?.cancel()
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val result = downloadManager.downloadGemma3Model()
            _isGemmaInstalled.value = result.isSuccess && downloadManager.isGemmaInstalled()
            refreshCapability()
        }
    }

    fun cancelGemmaDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun deleteGemmaModel() {
        downloadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            gemmaEngine.releaseMemory()
            downloadManager.deleteGemmaModel()
            _isGemmaInstalled.value = false
            refreshCapability()
        }
    }

    /**
     * Releases ~400MB active Gemma 3 inference RAM immediately when leaving NabdAiChatScreen.
     */
    fun releaseGemmaMemory() {
        viewModelScope.launch(Dispatchers.IO) {
            gemmaEngine.releaseMemory()
        }
    }

    fun sendChatMessage(
        rawQuery: String,
        categories: List<AzkarCategory>
    ) {
        val query = rawQuery.trim()
        if (query.isEmpty() || _isGenerating.value) return

        val userMessage = NabdAiChatMessage(
            id = messageIdCounter.getAndIncrement(),
            isUser = true,
            text = query
        )
        _chatMessages.update { it + userMessage }

        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            _isGenerating.value = true
            try {
                val reply = router.routeQuery(
                    rawQuery = query,
                    categories = categories,
                    offlineMetadata = _offlineMetadata.value
                )
                val botMessage = NabdAiChatMessage(
                    id = messageIdCounter.getAndIncrement(),
                    isUser = false,
                    text = reply.answerText,
                    sourceBadge = reply.sourceBadge,
                    sourceType = reply.sourceType,
                    linkedCategoryId = reply.linkedCategoryId,
                    linkedDhikrId = reply.linkedDhikrId,
                    linkedDhikrIndex = reply.linkedDhikrIndex,
                    matchedDhikrText = reply.matchedDhikrText,
                    usedGemma3 = reply.usedGemma3,
                    latencyMs = reply.latencyMs
                )
                _chatMessages.update { it + botMessage }
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun findLocalSearchMatches(query: String, categories: List<AzkarCategory>): List<LocalDhikrMatch> {
        val clean = query.trim()
        if (clean.length < 2 || router.isFatwaQuery(clean)) return emptyList()
        return router.findLocalMatches(clean, categories, _offlineMetadata.value, limit = 5)
    }

    fun isFatwaQuery(query: String): Boolean = router.isFatwaQuery(query)

    fun askAboutCurrentDhikr(
        question: String,
        categoryId: String,
        categoryName: String,
        azkar: Azkar,
        allCategories: List<AzkarCategory>
    ) {
        val clean = question.trim()
        if (clean.isEmpty()) return

        viewModelScope.launch {
            val startNanos = System.nanoTime()
            if (router.isFatwaQuery(clean)) {
                _sheetAnswer.value = NabdAiRouter.FATWA_REFUSAL_MESSAGE_AR
                _sheetSourceBadge.value = "تنبيه نطاق المساعد (بدون فتاوى)"
                _sheetAnswerLatencyMs.value = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                return@launch
            }

            val meta = _offlineMetadata.value["${categoryId}_${azkar.id}"]
            val normQ = router.normalizeArabic(clean)
            val directAnswer = resolveDirectDhikrQuestion(normQ, categoryName, azkar, meta)
            if (directAnswer != null) {
                _sheetAnswer.value = directAnswer
                _sheetSourceBadge.value = "المصدر: ${azkar.source} (محلي بدون إنترنت)"
                _sheetAnswerLatencyMs.value = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                return@launch
            }

            val reply = router.routeQuery(clean, allCategories, _offlineMetadata.value)
            _sheetAnswer.value = reply.answerText
            _sheetSourceBadge.value = reply.sourceBadge
            _sheetAnswerLatencyMs.value = reply.latencyMs
        }
    }

    private fun resolveDirectDhikrQuestion(
        normQ: String,
        categoryName: String,
        azkar: Azkar,
        meta: OfflineDhikrMetadata?
    ): String? {
        if (normQ.contains("معني") || normQ.contains("ما معني") || normQ.contains("what does it mean")) {
            val matchedVocab = meta?.vocabulary?.firstOrNull { vocab ->
                val wordNorm = router.normalizeArabic(vocab.word)
                wordNorm.split(" ").any { part -> part.length >= 3 && normQ.contains(part) }
            }
            if (matchedVocab != null) {
                return "معنى «${matchedVocab.word}»: ${matchedVocab.meaning}."
            }
            if (meta != null && meta.vocabulary.isNotEmpty()) {
                val summary = meta.vocabulary.joinToString("\n") { "• ${it.word}: ${it.meaning}" }
                return "أبرز معاني كلمات هذا الذكر:\n$summary"
            }
        }

        if (normQ.contains("كم مره") || normQ.contains("عدد") || normQ.contains("how many")) {
            val countText = when (azkar.repetition) {
                1 -> "مرة واحدة"
                2 -> "مرتين"
                in 3..10 -> "${azkar.repetition} مرات"
                else -> "${azkar.repetition} مرة"
            }
            return "يُقال هذا الذكر في «$categoryName» ($countText) كما ثبت في (${azkar.source})."
        }

        if (normQ.contains("فضل") || normQ.contains("اجر") || normQ.contains("ثواب")) {
            val virtue = meta?.shortVirtue?.ifBlank { azkar.virtue } ?: azkar.virtue
            return "فضل هذا الذكر: $virtue — المصدر: ${azkar.source}."
        }

        if (normQ.contains("متي") || normQ.contains("وقت") || normQ.contains("اداب") || normQ.contains("كيف")) {
            val detail = meta?.detailedExplanation
            if (!detail.isNullOrBlank()) return detail
        }

        if (normQ.contains("مصدر") || normQ.contains("سوره") || normQ.contains("راوي")) {
            val num = if (azkar.hadithNumber.isNotBlank()) " (رقم ${azkar.hadithNumber})" else ""
            return "المصدر المعتمد لهذا الذكر: ${azkar.source}$num."
        }

        return null
    }

    private suspend fun loadBundledMetadata() = withContext(Dispatchers.IO) {
        try {
            val type = object : TypeToken<Map<String, OfflineDhikrMetadata>>() {}.type
            val loaded: Map<String, OfflineDhikrMetadata> = getApplication<Application>()
                .assets
                .open("ai_metadata_ar.json")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> gson.fromJson(reader, type) } ?: emptyMap()
            _offlineMetadata.value = loaded
        } catch (_: Exception) {
            _offlineMetadata.value = emptyMap()
        }
    }

    override fun onCleared() {
        super.onCleared()
        gemmaEngine.close()
    }
}
