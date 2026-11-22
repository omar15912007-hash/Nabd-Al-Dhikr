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

data class GemmaManifest(
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    @SerializedName("release_tag") val releaseTag: String = "ai-v1.0.2",
    @SerializedName("model_name") val modelName: String = "Gemma 3 (270M) INT8",
    @SerializedName("filename") val filename: String = "gemma3-270m-nabd.task",
    @SerializedName("size_bytes") val sizeBytes: Long = 251_658_240L,
    @SerializedName("sha256") val sha256: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("min_ram_gb") val minRamGb: Float = 3.0f,
    @SerializedName("min_storage_mb") val minStorageMb: Long = 450L
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
    val totalRamBytes: Long,
    val totalRamGb: Double,
    val freeStorageMb: Long,
    val apiLevel: Int,
    val is64BitArm: Boolean,
    val supportedAbis: List<String>,
    val isLowRamDevice: Boolean,
    val checks: List<HardwareCheckItem>,
    val failedReasonsAr: List<String>,
    val status: DeviceCapabilityStatus,
    val statusHeadlineAr: String,
    val statusDetailAr: String,
    val canDownload: Boolean
)

sealed interface GemmaDownloadState {
    data object Idle : GemmaDownloadState
    data object FetchingManifest : GemmaDownloadState
    data class Downloading(
        val progress: Float,
        val downloadedMb: Float,
        val totalMb: Float
    ) : GemmaDownloadState
    data object Verifying : GemmaDownloadState
    data class Ready(val modelPath: String, val sizeMb: Float) : GemmaDownloadState
    data class Error(val message: String, val canResume: Boolean) : GemmaDownloadState
}

data class DorarHadithResult(
    val hadithText: String,
    val narrator: String,
    val scholar: String,
    val sourceBook: String,
    val numberOrPage: String,
    val ruling: String
)

enum class NabdAiSourceType {
    LOCAL_ADHKAR,
    DORAR_VERIFIED,
    INTERNET_REQUIRED,
    FATWA_BLOCKED
}

data class LocalDhikrMatch(
    val categoryId: String,
    val categoryName: String,
    val dhikrId: Int,
    val indexInCategory: Int,
    val dhikrText: String,
    val source: String,
    val hadithNumber: String,
    val shortVirtue: String,
    val detailedExplanation: String,
    val vocabulary: List<VocabEntry>,
    val suggestedFollowups: List<String>,
    val botAnswer: String,
    val repetition: Int,
    val score: Float
)

data class NabdAiReply(
    val answerText: String,
    val sourceBadge: String,
    val sourceType: NabdAiSourceType,
    val linkedCategoryId: String? = null,
    val linkedDhikrId: Int? = null,
    val linkedDhikrIndex: Int = 0,
    val matchedDhikrText: String? = null,
    val usedGemma3: Boolean = false,
    val latencyMs: Long = 0L
)

data class NabdAiChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val sourceBadge: String? = null,
    val sourceType: NabdAiSourceType? = null,
    val linkedCategoryId: String? = null,
    val linkedDhikrId: Int? = null,
    val linkedDhikrIndex: Int = 0,
    val matchedDhikrText: String? = null,
    val usedGemma3: Boolean = false,
    val latencyMs: Long = 0L
)
