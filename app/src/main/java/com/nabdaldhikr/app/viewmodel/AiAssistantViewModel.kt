package com.nabdaldhikr.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.aiPrefsDataStore by preferencesDataStore(name = "nabd_ai_prefs")

/**
 * Shared ViewModel coordinating:
 *  - Device compatibility checks (Storage >= 150 MB, RAM >= 2 GB)
 *  - Resumable GitHub Releases model + multilingual pack downloads (50+ languages)
 *  - Offline ONNX semantic engine + bundled instant metadata for the ✨ BottomSheet
 *  - Home & Search semantic query state
 */
class AiAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()
    private val prefs = application.aiPrefsDataStore
    private val activeLangKey = stringPreferencesKey("active_ai_language")

    val downloadManager = ModelDownloadManager(application, gson)
    private val aiEngine = OfflineDhikrAI(gson)

    private val _capabilityReport = MutableStateFlow(DeviceCapabilityChecker.inspect(application))
    val capabilityReport: StateFlow<DeviceCapabilityReport> = _capabilityReport.asStateFlow()

    val downloadState: StateFlow<AiDownloadState> = downloadManager.state

    private val _isNeuralModelReady = MutableStateFlow(false)
    val isNeuralModelReady: StateFlow<Boolean> = _isNeuralModelReady.asStateFlow()

    private val _activeLanguageCode = MutableStateFlow("ar")
    val activeLanguageCode: StateFlow<String> = _activeLanguageCode.asStateFlow()

    private val _installedLanguages = MutableStateFlow<Set<String>>(emptySet())
    val installedLanguages: StateFlow<Set<String>> = _installedLanguages.asStateFlow()

    private val _availableLanguages = MutableStateFlow(defaultSupportedLanguages())
    val availableLanguages: StateFlow<List<LanguageOption>> = _availableLanguages.asStateFlow()

    private val _offlineMetadata = MutableStateFlow<Map<String, OfflineDhikrMetadata>>(emptyMap())
    val offlineMetadata: StateFlow<Map<String, OfflineDhikrMetadata>> = _offlineMetadata.asStateFlow()

    private val _homeSearchOutcome = MutableStateFlow<AiQueryOutcome?>(null)
    val homeSearchOutcome: StateFlow<AiQueryOutcome?> = _homeSearchOutcome.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _sheetAnswer = MutableStateFlow<String?>(null)
    val sheetAnswer: StateFlow<String?> = _sheetAnswer.asStateFlow()

    private val _sheetAnswerLatencyMs = MutableStateFlow(0L)
    val sheetAnswerLatencyMs: StateFlow<Long> = _sheetAnswerLatencyMs.asStateFlow()

    private var downloadJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            loadBundledMetadata()
            val savedLang = prefs.data.first()[activeLangKey] ?: "ar"
            _activeLanguageCode.value = savedLang
            refreshInstalledState(autoLoadLanguage = savedLang)
        }
    }

    fun refreshCapability() {
        _capabilityReport.value = DeviceCapabilityChecker.inspect(getApplication())
    }

    fun getMetadataForDhikr(categoryId: String, dhikrId: Int): OfflineDhikrMetadata? {
        return _offlineMetadata.value["${categoryId}_$dhikrId"]
    }

    fun clearSheetAnswer() {
        _sheetAnswer.value = null
        _sheetAnswerLatencyMs.value = 0L
    }

    fun clearHomeSearch() {
        searchJob?.cancel()
        _homeSearchOutcome.value = null
        _isSearching.value = false
    }

    fun refreshManifestLanguages() {
        viewModelScope.launch {
            val result = downloadManager.fetchManifest()
            result.onSuccess { manifest ->
                val releaseMap = manifest.languages.associateBy { it.code }
                val merged = defaultSupportedLanguages().map { opt ->
                    val pack = releaseMap[opt.code]
                    if (pack != null) {
                        opt.copy(
                            nativeName = pack.nativeName.ifBlank { opt.nativeName },
                            englishName = pack.englishName.ifBlank { opt.englishName },
                            isAvailableInRelease = true
                        )
                    } else {
                        opt
                    }
                }
                val extras = manifest.languages
                    .filter { pack -> merged.none { it.code == pack.code } }
                    .map { pack ->
                        LanguageOption(
                            code = pack.code,
                            nativeName = pack.nativeName,
                            englishName = pack.englishName,
                            isRtl = pack.rtl,
                            isAvailableInRelease = true
                        )
                    }
                _availableLanguages.value = merged + extras
            }
        }
    }

    fun startModelDownload(languageCode: String = _activeLanguageCode.value) {
        // Live hardware compatibility guard — hard block if device does not qualify
        val liveCapability = DeviceCapabilityChecker.inspect(getApplication())
        _capabilityReport.value = liveCapability
        if (!liveCapability.canDownload) {
            downloadJob?.cancel()
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val result = downloadManager.downloadModelAndLanguagePack(languageCode)
            if (result.isSuccess) {
                setActiveLanguage(languageCode)
                refreshInstalledState(autoLoadLanguage = languageCode)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun setActiveLanguage(languageCode: String) {
        _activeLanguageCode.value = languageCode
        viewModelScope.launch {
            prefs.edit { it[activeLangKey] = languageCode }
            if (downloadManager.isLanguagePackInstalled(languageCode)) {
                activateNeuralLanguage(languageCode)
            }
        }
    }

    fun deleteLanguagePack(languageCode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadManager.deleteLanguagePack(languageCode)
            val remaining = downloadManager.getInstalledLanguages()
            _installedLanguages.value = remaining
            if (remaining.isEmpty()) {
                aiEngine.close()
                _isNeuralModelReady.value = false
            } else if (_activeLanguageCode.value == languageCode) {
                val fallback = remaining.first()
                setActiveLanguage(fallback)
            }
            refreshCapability()
        }
    }

    fun deleteAllModels() {
        downloadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            aiEngine.close()
            downloadManager.deleteAllAiAssets()
            _isNeuralModelReady.value = false
            _installedLanguages.value = emptySet()
            refreshCapability()
        }
    }

    /**
     * Answers a follow-up chip or free-text user question inside the Dhikr detail BottomSheet.
     * Enforces zero-fatwa guardrail and 0.65 similarity threshold.
     */
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
            if (aiEngine.isFatwaOrOffTopicQuery(clean)) {
                _sheetAnswer.value = OfflineDhikrAI.POLITE_FALLBACK_MESSAGE_AR
                _sheetAnswerLatencyMs.value = (System.nanoTime() - startNanos) / 1_000_000L
                return@launch
            }

            val meta = getMetadataForDhikr(categoryId, azkar.id)
            val normQ = aiEngine.normalizeArabic(clean)

            // Fast targeted resolution for current-dhikr intent chips (word meaning, count, virtue, timing, source)
            val directAnswer = resolveDirectDhikrQuestion(normQ, categoryName, azkar, meta)
            if (directAnswer != null) {
                _sheetAnswer.value = directAnswer
                _sheetAnswerLatencyMs.value = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                return@launch
            }

            // Full neural / semantic engine query across the entire Adhkar dataset
            when (val outcome = aiEngine.search(clean, allCategories, _offlineMetadata.value)) {
                is AiQueryOutcome.Match -> {
                    _sheetAnswer.value = outcome.bestMatch.botAnswer
                    _sheetAnswerLatencyMs.value = outcome.latencyMs
                }
                is AiQueryOutcome.BlockedOutOfScope -> {
                    _sheetAnswer.value = outcome.message
                    _sheetAnswerLatencyMs.value = outcome.latencyMs
                }
                AiQueryOutcome.NoData -> {
                    _sheetAnswer.value = OfflineDhikrAI.POLITE_FALLBACK_MESSAGE_AR
                    _sheetAnswerLatencyMs.value = 0L
                }
            }
        }
    }

    /**
     * Runs Smart Semantic Search from HomeScreen or SearchScreen.
     */
    fun runSemanticSearch(query: String, categories: List<AzkarCategory>) {
        searchJob?.cancel()
        val clean = query.trim()
        if (clean.length < 2) {
            _homeSearchOutcome.value = null
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            _isSearching.value = true
            val outcome = aiEngine.search(clean, categories, _offlineMetadata.value, topK = 4)
            _homeSearchOutcome.value = outcome
            _isSearching.value = false
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
                val wordNorm = aiEngine.normalizeArabic(vocab.word)
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

        if (normQ.contains("متي") || normQ.contains("وقت") || normQ.contains("اداب") || normQ.contains("كيف") || normQ.contains("when")) {
            val detail = meta?.detailedExplanation
            if (!detail.isNullOrBlank()) {
                return detail
            }
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

    private suspend fun refreshInstalledState(autoLoadLanguage: String) {
        val installed = downloadManager.getInstalledLanguages()
        _installedLanguages.value = installed
        val targetLang = when {
            downloadManager.isLanguagePackInstalled(autoLoadLanguage) -> autoLoadLanguage
            installed.isNotEmpty() -> installed.first()
            else -> null
        }
        if (targetLang != null) {
            activateNeuralLanguage(targetLang)
        } else {
            _isNeuralModelReady.value = false
        }
    }

    private suspend fun activateNeuralLanguage(languageCode: String) {
        val result = aiEngine.loadNeuralPack(
            modelFile = downloadManager.modelFile(),
            tokenizerFile = downloadManager.tokenizerFile(),
            vectorsFile = downloadManager.vectorsFile(languageCode),
            datasetFile = downloadManager.datasetFile(languageCode),
            languageCode = languageCode
        )
        _isNeuralModelReady.value = result.isSuccess
    }

    override fun onCleared() {
        super.onCleared()
        aiEngine.close()
    }

    private fun defaultSupportedLanguages(): List<LanguageOption> = listOf(
        LanguageOption("ar", "العربية", "Arabic", isRtl = true, isAvailableInRelease = true),
        LanguageOption("en", "English", "English", isRtl = false, isAvailableInRelease = true),
        LanguageOption("ur", "اردو", "Urdu", isRtl = true),
        LanguageOption("id", "Bahasa Indonesia", "Indonesian", isRtl = false),
        LanguageOption("tr", "Türkçe", "Turkish", isRtl = false),
        LanguageOption("fr", "Français", "French", isRtl = false),
        LanguageOption("ms", "Bahasa Melayu", "Malay", isRtl = false),
        LanguageOption("bn", "বাংলা", "Bengali", isRtl = false),
        LanguageOption("fa", "فارسی", "Persian", isRtl = true),
        LanguageOption("ru", "Русский", "Russian", isRtl = false),
        LanguageOption("de", "Deutsch", "German", isRtl = false),
        LanguageOption("es", "Español", "Spanish", isRtl = false),
        LanguageOption("pt", "Português", "Portuguese", isRtl = false),
        LanguageOption("it", "Italiano", "Italian", isRtl = false),
        LanguageOption("nl", "Nederlands", "Dutch", isRtl = false),
        LanguageOption("sw", "Kiswahili", "Swahili", isRtl = false),
        LanguageOption("ha", "Hausa", "Hausa", isRtl = false),
        LanguageOption("so", "Soomaali", "Somali", isRtl = false),
        LanguageOption("bs", "Bosanski", "Bosnian", isRtl = false),
        LanguageOption("sq", "Shqip", "Albanian", isRtl = false),
        LanguageOption("uz", "Oʻzbekcha", "Uzbek", isRtl = false),
        LanguageOption("az", "Azərbaycan", "Azerbaijani", isRtl = false),
        LanguageOption("hi", "हिन्दी", "Hindi", isRtl = false),
        LanguageOption("zh", "中文", "Chinese", isRtl = false)
    )
}
