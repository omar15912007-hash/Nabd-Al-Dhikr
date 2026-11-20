package com.nabdaldhikr.app.ai

import com.nabdaldhikr.app.data.model.Azkar
import com.nabdaldhikr.app.data.model.AzkarCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class OfflineAiEngineTest {

    @Test
    fun deviceCapability_allowsDownloadOnlyWhenAll5ChecksPass() {
        val report = DeviceCapabilityChecker.evaluateCapability(
            freeStorageMb = 512L,
            totalRamMb = 4096L,
            availableRamMb = 1800L,
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            isLowRamDevice = false,
            isLowMemoryNow = false
        )
        assertEquals(DeviceCapabilityStatus.COMPATIBLE, report.status)
        assertEquals("متوافق تماماً مع جهازك", report.statusTitleAr)
        assertTrue(report.canDownload)
        assertEquals(5, report.checks.size)
        assertTrue(report.checks.all { it.passed })
        assertTrue(report.failedReasonsAr.isEmpty())
    }

    @Test
    fun deviceCapability_hardBlocksWhenRamBelow2Gb() {
        val report = DeviceCapabilityChecker.evaluateCapability(
            freeStorageMb = 600L,
            totalRamMb = 1536L,
            availableRamMb = 600L,
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a"),
            isLowRamDevice = false,
            isLowMemoryNow = false
        )
        assertEquals(DeviceCapabilityStatus.INCOMPATIBLE, report.status)
        assertFalse(report.canDownload)
        assertTrue(report.failedReasonsAr.isNotEmpty())
    }

    @Test
    fun deviceCapability_hardBlocksLowRamGoDevice() {
        val report = DeviceCapabilityChecker.evaluateCapability(
            freeStorageMb = 600L,
            totalRamMb = 2048L,
            availableRamMb = 800L,
            cpuCores = 4,
            supportedAbis = listOf("arm64-v8a"),
            isLowRamDevice = true,
            isLowMemoryNow = false
        )
        assertEquals(DeviceCapabilityStatus.INCOMPATIBLE, report.status)
        assertFalse(report.canDownload)
    }

    @Test
    fun deviceCapability_hardBlocksWhenStorageBelow250Mb() {
        val report = DeviceCapabilityChecker.evaluateCapability(
            freeStorageMb = 200L,
            totalRamMb = 6144L,
            availableRamMb = 2500L,
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a"),
            isLowRamDevice = false,
            isLowMemoryNow = false
        )
        assertEquals(DeviceCapabilityStatus.INCOMPATIBLE, report.status)
        assertFalse(report.canDownload)
    }

    @Test
    fun deviceCapability_hardBlocksWhenAvailableRamOrCpuCoresInsufficient() {
        val lowAvail = DeviceCapabilityChecker.evaluateCapability(
            freeStorageMb = 800L,
            totalRamMb = 4096L,
            availableRamMb = 200L,
            cpuCores = 8,
            supportedAbis = listOf("arm64-v8a"),
            isLowRamDevice = false,
            isLowMemoryNow = false
        )
        assertFalse(lowAvail.canDownload)

        val dualCore = DeviceCapabilityChecker.evaluateCapability(
            freeStorageMb = 800L,
            totalRamMb = 4096L,
            availableRamMb = 1200L,
            cpuCores = 2,
            supportedAbis = listOf("arm64-v8a"),
            isLowRamDevice = false,
            isLowMemoryNow = false
        )
        assertFalse(dualCore.canDownload)

        val unsupportedAbi = DeviceCapabilityChecker.evaluateCapability(
            freeStorageMb = 800L,
            totalRamMb = 4096L,
            availableRamMb = 1200L,
            cpuCores = 8,
            supportedAbis = listOf("x86"),
            isLowRamDevice = false,
            isLowMemoryNow = false
        )
        assertFalse(unsupportedAbi.canDownload)
    }

    @Test
    fun sentencePieceUnigram_encodesArabicWithBosAndEos() {
        val sampleTokenizerJson = """
            {
              "model": {
                "type": "Unigram",
                "unk_id": 3,
                "vocab": [
                  ["<s>", 0.0],
                  ["<pad>", 0.0],
                  ["</s>", 0.0],
                  ["<unk>", 0.0],
                  ["\u2581ذكر", -1.2],
                  ["\u2581الله", -1.1],
                  ["\u2581", -2.5]
                ]
              }
            }
        """.trimIndent()

        val tokenizer = SentencePieceTokenizer.fromJsonString(sampleTokenizerJson)
        val encoded = tokenizer.encode("ذكر الله")
        assertArrayEquals(longArrayOf(0L, 4L, 5L, 2L), encoded.inputIds)
        assertArrayEquals(longArrayOf(1L, 1L, 1L, 1L), encoded.attentionMask)
    }

    @Test
    fun offlineAi_blocksFatwaQueriesAndOffTopicBelowThreshold() = runBlocking {
        val engine = OfflineDhikrAI()
        val categories = listOf(
            AzkarCategory(
                id = "worry_azkar",
                nameAr = "دعاء الهم والحزن",
                nameEn = "Worry Dua",
                azkarList = listOf(
                    Azkar(
                        id = 1701,
                        text = "اللَّهُمَّ إِنِّي عَبْدُكَ... أَنْ تَجْعَلَ الْقُرْآنَ رَبِيعَ قَلْبِي وَجَلَاءَ حُزْنِي وَذَهَابَ هَمِّي",
                        source = "أحمد وابن حبان",
                        virtue = "أذهب الله همه وحزنه وأبدله فرحاً.",
                        repetition = 1
                    )
                )
            )
        )

        val fatwaOutcome = engine.search("هل يجوز شرعا أخذ قرض وما حكم الشرع؟", categories, emptyMap())
        assertTrue(fatwaOutcome is AiQueryOutcome.BlockedOutOfScope)

        val offTopicOutcome = engine.search("أفضل طريقة لتحضير البيتزا الإيطالية", categories, emptyMap())
        assertTrue(offTopicOutcome is AiQueryOutcome.BlockedOutOfScope)

        val emotionalOutcome = engine.search("قلقان ومخنوق جداً", categories, emptyMap())
        assertTrue(emotionalOutcome is AiQueryOutcome.Match)
        val match = (emotionalOutcome as AiQueryOutcome.Match).bestMatch
        assertEquals("worry_azkar", match.categoryId)
        assertTrue(match.similarityScore >= OfflineDhikrAI.SIMILARITY_THRESHOLD)
        engine.close()
    }

    @Test
    fun offlineAi_l2NormalizeAndFp16DecodeAreAccurate() {
        val engine = OfflineDhikrAI()
        val norm = engine.l2Normalize(floatArrayOf(3f, 4f))
        assertTrue(abs(norm[0] - 0.6f) < 1e-5f)
        assertTrue(abs(norm[1] - 0.8f) < 1e-5f)

        assertEquals(1.0f, engine.halfBitsToFloat(0x3C00), 1e-5f)
        assertEquals(-0.5f, engine.halfBitsToFloat(0xB800), 1e-5f)
        engine.close()
    }
}
