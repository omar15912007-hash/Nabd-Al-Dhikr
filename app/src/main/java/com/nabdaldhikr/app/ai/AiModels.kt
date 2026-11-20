package com.nabdaldhikr.app.ai

import com.google.gson.annotations.SerializedName

data class VocabEntry(
    @SerializedName("word") val word: String = "",
    @SerializedName("meaning") val meaning: String = ""
)

data class QaPair(
    @SerializedName("intent") val intent: String = "",
    @SerializedName("user_questions") val userQuestions: List<String> = emptyList(),
    @SerializedName("bot_answer") val botAnswer: String = ""
)

data class AiDhikrEntry(
    @SerializedName("id") val id: String = "",
    @SerializedName("order_index") val orderIndex: Int = 1,
    @SerializedName("language") val language: String = "ar",
    @SerializedName("category") val category: String = "",
    @SerializedName("audit_status") val auditStatus: String = "valid",
    @SerializedName("audit_notes") val auditNotes: String = "",
    @SerializedName("dhikr_text") val dhikrText: String = "",
    @SerializedName("transliteration") val transliteration: String = "",
    @SerializedName("repeat_count_number") val repeatCountNumber: Int = 1,
    @SerializedName("repeat_count_label") val repeatCountLabel: String = "",
    @SerializedName("source_reference") val sourceReference: String = "",
    @SerializedName("short_virtue_for_card") val shortVirtueForCard: String = "",
    @SerializedName("detailed_explanation") val detailedExplanation: String = "",
    @SerializedName("vocabulary_explanation") val vocabularyExplanation: List<VocabEntry> = emptyList(),
    @SerializedName("qa_pairs") val qaPairs: List<QaPair> = emptyList(),
    @SerializedName("suggested_followups") val suggestedFollowups: List<String> = emptyList()
)

data class OfflineDhikrMetadata(
    @SerializedName("aiId") val aiId: String = "",
    @SerializedName("categoryId") val categoryId: String = "",
    @SerializedName("categoryName") val categoryName: String = "",
    @SerializedName("dhikrId") val dhikrId: Int = 0,
    @SerializedName("orderIndex") val orderIndex: Int = 1,
    @SerializedName("auditStatus") val auditStatus: String = "valid",
    @SerializedName("shortVirtue") val shortVirtue: String = "",
    @SerializedName("detailedExplanation") val detailedExplanation: String = "",
    @SerializedName("vocabulary") val vocabulary: List<VocabEntry> = emptyList(),
    @SerializedName("suggestedFollowups") val suggestedFollowups: List<String> = emptyList(),
    @SerializedName("botAnswer") val botAnswer: String = "",
    @SerializedName("keywords") val keywords: List<String> = emptyList()
)

data class AiAssetFile(
    @SerializedName("filename") val filename: String = "",
    @SerializedName("size_bytes") val sizeBytes: Long = 0L,
    @SerializedName("sha256") val sha256: String = "",
    @SerializedName("url") val url: String = ""
)

data class LanguagePackInfo(
    @SerializedName("code") val code: String = "",
    @SerializedName("native_name") val nativeName: String = "",
    @SerializedName("english_name") val englishName: String = "",
    @SerializedName("rtl") val rtl: Boolean = false,
    @SerializedName("entry_count") val entryCount: Int = 0,
    @SerializedName("vector_count") val vectorCount: Int = 0,
    @SerializedName("vectors") val vectors: AiAssetFile = AiAssetFile(),
    @SerializedName("dataset") val dataset: AiAssetFile = AiAssetFile()
)

data class AiManifest(
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    @SerializedName("release_tag") val releaseTag: String = "ai-v1.0.0",
    @SerializedName("model_id") val modelId: String = "",
    @SerializedName("embedding_dim") val embeddingDim: Int = 384,
    @SerializedName("similarity_threshold") val similarityThreshold: Float = 0.65f,
    @SerializedName("generated_at_utc") val generatedAtUtc: String = "",
    @SerializedName("model") val model: AiAssetFile = AiAssetFile(),
    @SerializedName("tokenizer") val tokenizer: AiAssetFile = AiAssetFile(),
    @SerializedName("languages") val languages: List<LanguagePackInfo> = emptyList()
)

data class LanguageOption(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val isRtl: Boolean,
    val isAvailableInRelease: Boolean = false
)

enum class DeviceCapabilityStatus {
    COMPATIBLE,
    INCOMPATIBLE
}

data class HardwareCheckItem(
    val labelAr: String,
    val actualValueAr: String,
    val requirementAr: String,
    val passed: Boolean
)

data class DeviceCapabilityReport(
    val freeStorageMb: Long,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val cpuCores: Int,
    val supportedAbis: List<String>,
    val isLowRamDevice: Boolean,
    val isLowMemoryNow: Boolean,
    val checks: List<HardwareCheckItem>,
    val failedReasonsAr: List<String>,
    val status: DeviceCapabilityStatus,
    val statusTitleAr: String,
    val statusDetailAr: String,
    val canDownload: Boolean
)

sealed interface AiDownloadState {
    data object Idle : AiDownloadState
    data object FetchingManifest : AiDownloadState
    data class Downloading(
        val stageLabel: String,
        val currentFile: String,
        val progress: Float,
        val downloadedMb: Float,
        val totalMb: Float
    ) : AiDownloadState
    data class Verifying(val fileName: String) : AiDownloadState
    data class Ready(val languageCode: String, val installedLanguages: Set<String>) : AiDownloadState
    data class Error(val message: String, val canResume: Boolean) : AiDownloadState
}

data class SemanticMatch(
    val dhikrId: Int,
    val categoryId: String,
    val categoryName: String,
    val orderIndex: Int,
    val dhikrText: String,
    val shortVirtue: String,
    val detailedExplanation: String,
    val botAnswer: String,
    val vocabulary: List<VocabEntry>,
    val suggestedFollowups: List<String>,
    val similarityScore: Float,
    val usedNeuralEngine: Boolean
)

sealed interface AiQueryOutcome {
    data class Match(
        val bestMatch: SemanticMatch,
        val alternatives: List<SemanticMatch>,
        val latencyMs: Long
    ) : AiQueryOutcome

    data class BlockedOutOfScope(
        val message: String,
        val topScore: Float,
        val latencyMs: Long
    ) : AiQueryOutcome

    data object NoData : AiQueryOutcome
}
