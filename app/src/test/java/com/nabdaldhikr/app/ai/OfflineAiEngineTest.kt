package com.nabdaldhikr.app.ai

import com.nabdaldhikr.app.data.model.Azkar
import com.nabdaldhikr.app.data.model.AzkarCategory
import com.nabdaldhikr.app.util.QiblaMathUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class OfflineAiEngineTest {

    // =========================================================================
    // 1. Device Capability Checker Tests (3GB RAM, 450MB Storage, API 26+, arm64-v8a)
    // =========================================================================

    @Test
    fun deviceCapability_compatibleWith3GbRam450MbStorageApi26Arm64() {
        val report = DeviceCapabilityChecker.evaluateCapability(
            totalRamBytes = 4L * 1024L * 1024L * 1024L,
            freeStorageMb = 900L,
            apiLevel = 34,
            is64BitArm = true,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )
        assertEquals(DeviceCapabilityStatus.COMPATIBLE, report.status)
        assertTrue(report.canDownload)
        assertTrue(report.statusHeadlineAr.contains("جهازك متوافق تماماً ✅"))
        assertTrue(report.statusHeadlineAr.contains("4.0 جيجا"))
        assertTrue(report.statusHeadlineAr.contains("900 ميجا"))
    }

    @Test
    fun deviceCapability_blocksWhenRamBelow3GbOrStorageBelow450MbOr32Bit() {
        val lowRam = DeviceCapabilityChecker.evaluateCapability(
            totalRamBytes = 2L * 1024L * 1024L * 1024L,
            freeStorageMb = 1000L,
            apiLevel = 33,
            is64BitArm = true,
            supportedAbis = listOf("arm64-v8a")
        )
        assertEquals(DeviceCapabilityStatus.INCOMPATIBLE, lowRam.status)
        assertFalse(lowRam.canDownload)
        assertTrue(lowRam.statusHeadlineAr.contains("3 جيجا رام و 450 ميجا مساحة"))

        val lowStorage = DeviceCapabilityChecker.evaluateCapability(
            totalRamBytes = 6L * 1024L * 1024L * 1024L,
            freeStorageMb = 400L,
            apiLevel = 33,
            is64BitArm = true,
            supportedAbis = listOf("arm64-v8a")
        )
        assertFalse(lowStorage.canDownload)

        val non64Bit = DeviceCapabilityChecker.evaluateCapability(
            totalRamBytes = 6L * 1024L * 1024L * 1024L,
            freeStorageMb = 1200L,
            apiLevel = 33,
            is64BitArm = false,
            supportedAbis = listOf("armeabi-v7a")
        )
        assertFalse(non64Bit.canDownload)

        val oldOs = DeviceCapabilityChecker.evaluateCapability(
            totalRamBytes = 4L * 1024L * 1024L * 1024L,
            freeStorageMb = 1200L,
            apiLevel = 25,
            is64BitArm = true,
            supportedAbis = listOf("arm64-v8a")
        )
        assertFalse(oldOs.canDownload)
    }

    // =========================================================================
    // 2. Smart Query Routing + Anti-Fatwa Guardrail + Offline/Online Dorar Tests
    // =========================================================================

    private val sampleCategories = listOf(
        AzkarCategory(
            id = "morning_azkar",
            nameAr = "أذكار الصباح",
            nameEn = "Morning Azkar",
            azkarList = listOf(
                Azkar(
                    id = 101,
                    text = "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ",
                    source = "سورة البقرة: 255",
                    virtue = "من قالها حين يصبح أجير من الجن حتى يمسي.",
                    repetition = 1
                )
            )
        ),
        AzkarCategory(
            id = "worry_azkar",
            nameAr = "دعاء الهم والحزن",
            nameEn = "Worry Dua",
            azkarList = listOf(
                Azkar(
                    id = 1701,
                    text = "اللَّهُمَّ إِنِّي عَبْدُكَ أَنْ تَجْعَلَ الْقُرْآنَ رَبِيعَ قَلْبِي وَجَلَاءَ حُزْنِي وَذَهَابَ هَمِّي",
                    source = "أحمد وابن حبان",
                    virtue = "أذهب الله همه وحزنه وأبدله فرحاً.",
                    repetition = 1
                )
            )
        )
    )

    @Test
    fun nabdRouter_blocksFatwasButNeverBlocksMorningAzkarOrEmotionalQueries() = runBlocking {
        val offlineProbe = object : ConnectivityProbe { override fun isOnline(): Boolean = false }
        val router = NabdAiRouter(connectivityProbe = offlineProbe)

        // Explicit fatwas MUST be blocked
        for (fatwa in listOf("هل حرام أخذ قرض؟", "ما حكم الطلاق في الغضب؟", "أعطني فتوى هل يجوز هذا؟")) {
            val reply = router.routeQuery(fatwa, sampleCategories, emptyMap())
            assertEquals("Expected FATWA_BLOCKED for: $fatwa", NabdAiSourceType.FATWA_BLOCKED, reply.sourceType)
        }

        // Valid short Dhikr queries MUST NEVER be blocked and MUST answer offline
        val morningReply = router.routeQuery("أذكار الصباح", sampleCategories, emptyMap())
        assertEquals(NabdAiSourceType.LOCAL_ADHKAR, morningReply.sourceType)
        assertEquals("morning_azkar", morningReply.linkedCategoryId)

        val worryReply = router.routeQuery("قلقان ومخنوق جداً", sampleCategories, emptyMap())
        assertEquals(NabdAiSourceType.LOCAL_ADHKAR, worryReply.sourceType)
        assertEquals("worry_azkar", worryReply.linkedCategoryId)
    }

    @Test
    fun nabdRouter_returnsExactInternetRequiredMessageWhenOfflineForExternalQuery() = runBlocking {
        val offlineProbe = object : ConnectivityProbe { override fun isOnline(): Boolean = false }
        val router = NabdAiRouter(connectivityProbe = offlineProbe)

        val externalReply = router.routeQuery(
            "ما صحة حديث من لزم الاستغفار جعل الله له من كل هم فرجا؟",
            sampleCategories,
            emptyMap()
        )
        assertEquals(NabdAiSourceType.INTERNET_REQUIRED, externalReply.sourceType)
        assertEquals(NabdAiRouter.INTERNET_REQUIRED_MESSAGE_AR, externalReply.answerText)
    }

    // =========================================================================
    // 3. Dorar.net API Parser & Strict Sahih/Hasan Authenticity Filter Tests
    // =========================================================================

    @Test
    fun dorarParser_extractsFieldsAndFiltersOutWeakAndFabricatedNarrations() {
        val client = DorarApiClient()
        val mockPayload = """
            {
              "ahadith": {
                "result": "<div class=\"hadith\">1 - كلمتان خفيفتان على اللسان</div><div class=\"hadith-info\">الراوي: أبو هريرة | المحدث: البخاري | المصدر: صحيح البخاري | الصفحة أو الرقم: 6406 | خلاصة حكم المحدث: [صحيح]</div><div class=\"hadith\">2 - حديث موضوع لا أصل له</div><div class=\"hadith-info\">الراوي: فلان | المحدث: الألباني | المصدر: السلسلة الضعيفة | الصفحة أو الرقم: 99 | خلاصة حكم المحدث: ضعيف جداً</div>"
              }
            }
        """.trimIndent()

        val parsed = client.parseDorarResponse(mockPayload)
        assertEquals(2, parsed.size)
        assertEquals("صحيح البخاري", parsed[0].sourceBook)
        assertTrue(client.isAuthenticRuling(parsed[0].ruling))
        assertFalse(client.isAuthenticRuling(parsed[1].ruling))
        assertFalse(client.isAuthenticRuling("موضوع"))
        assertFalse(client.isAuthenticRuling("إسناده ضعيف"))
        assertTrue(client.isAuthenticRuling("حسن لغيره"))
    }

    // =========================================================================
    // 4. Qibla 3D Vector Math & AR Camera Zero-180-Flip Proofs
    // =========================================================================

    @Test
    fun qiblaMath_extractHorizontalHeadingMatchesTrueCardinalDirectionsFlatTiltedAndArCamera() {
        // 1. Flat on table facing North: +X=East(1,0,0), +Y=North(0,1,0), +Z=Up(0,0,1)
        val flatNorth = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        assertEquals(0f, QiblaMathUtils.extractHorizontalHeading(flatNorth, isCameraMode = false), 0.01f)

        // 2. Upright AR Camera facing NORTH: +X=East(1,0,0), +Y=Up(0,0,1), +Z(screen)=South(0,-1,0), -Z(rear camera)=North(0,+1,0)
        val uprightCameraNorth = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        assertEquals(0f, QiblaMathUtils.extractHorizontalHeading(uprightCameraNorth, isCameraMode = true), 0.01f)
        assertEquals(0f, QiblaMathUtils.extractHorizontalHeading(uprightCameraNorth, isCameraMode = false), 0.01f)
        assertEquals(0f, QiblaMathUtils.extractCameraElevationDegrees(uprightCameraNorth), 0.01f)

        // 3. Upright AR Camera facing EAST: +X=South(0,-1,0), +Y=Up(0,0,1), +Z=West(-1,0,0), -Z(rear camera)=East(+1,0,0)
        val uprightCameraEast = floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f
        )
        assertEquals(90f, QiblaMathUtils.extractHorizontalHeading(uprightCameraEast, isCameraMode = true), 0.01f)

        // 4. Tilted 45° toward user facing NORTH: +Y=(0, s, s), -Z=(0, s, -s) where s=1/sqrt(2)
        val s = (1.0 / sqrt(2.0)).toFloat()
        val tilted45North = floatArrayOf(
            1f, 0f, 0f,
            0f, s, -s,
            0f, s, s
        )
        assertEquals(0f, QiblaMathUtils.extractHorizontalHeading(tilted45North, isCameraMode = false), 0.01f)
        assertEquals(0f, QiblaMathUtils.extractHorizontalHeading(tilted45North, isCameraMode = true), 0.01f)
    }

    @Test
    fun qiblaMath_greatCircleBearingAccurateAcrossGlobalCities() {
        // Cairo -> Makkah (~136.1°)
        val cairo = QiblaMathUtils.calculateQiblaBearing(30.0444, 31.2357)
        assertTrue("Cairo Qibla should be ~136° but got $cairo", abs(cairo - 136.1f) < 1.0f)

        // Riyadh -> Makkah (~244.4°)
        val riyadh = QiblaMathUtils.calculateQiblaBearing(24.7136, 46.6753)
        assertTrue("Riyadh Qibla should be ~244.4° but got $riyadh", abs(riyadh - 244.4f) < 1.0f)

        // London -> Makkah (~119.0°)
        val london = QiblaMathUtils.calculateQiblaBearing(51.5074, -0.1278)
        assertTrue("London Qibla should be ~119.0° but got $london", abs(london - 119.0f) < 1.0f)

        // Magnetic field interference detector
        assertFalse(QiblaMathUtils.isMagneticFieldInterfered(45f))
        assertTrue(QiblaMathUtils.isMagneticFieldInterfered(120f))
        assertTrue(QiblaMathUtils.isMagneticFieldInterfered(10f))
    }
}
