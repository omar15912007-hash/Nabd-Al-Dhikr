package com.nabdaldhikr.app.ai

import com.nabdaldhikr.app.data.model.Azkar
import com.nabdaldhikr.app.data.model.AzkarCategory
import com.nabdaldhikr.app.util.QiblaMathUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
                    hadithNumber = "3712",
                    virtue = "أذهب الله همه وحزنه وأبدله فرحاً.",
                    repetition = 1
                ),
                Azkar(
                    id = 1702,
                    text = "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْهَمِّ وَالْحَزَنِ، وَالْعَجْزِ وَالْكَسَلِ",
                    source = "صحيح البخاري",
                    hadithNumber = "6369",
                    virtue = "كان النبي ﷺ يكثر من هذا الدعاء العظيم.",
                    repetition = 1
                ),
                Azkar(
                    id = 1706,
                    text = "لَا إِلَهَ إِلاَّ اللَّهُ الْعَظِيمُ الْحَلِيمُ، لاَ إِلَهَ إِلاَّ اللَّهُ رَبُّ الْعَرْشِ الْعَظِيمِ، لاَ إِلَهَ إِلاَّ اللَّهُ رَبُّ السَّمَاوَاتِ وَرَبُّ الأَرْضِ وَرَبُّ الْعَرْشِ الْكَرِيمِ.",
                    source = "صحيح البخاري ومسلم",
                    hadithNumber = "6346",
                    virtue = "كان النبي ﷺ يدعو بها عند الكرب والحزن الشديد فيفرج الله به الهم.",
                    repetition = 1
                )
            )
        ),
        AzkarCategory(
            id = "home_enter",
            nameAr = "دخول المنزل",
            nameEn = "Entering Home",
            azkarList = listOf(
                Azkar(
                    id = 902,
                    text = "بِسْمِ اللَّهِ وَلَجْنَا وَبِسْمِ اللَّهِ خَرَجْنَا وَعَلَى رَبِّنَا تَوَكَّلْنَا، ثُمَّ لِيُسَلِّمْ عَلَى أَهْلِهِ.",
                    source = "أبو داود",
                    hadithNumber = "5096",
                    virtue = "ذكر دخول المنزل والسلام على الأهل.",
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

    /**
     * Builds exact Android ENU 3x3 rotation matrix R = Rz(-heading) * Rx(pitch) * Ry(roll)
     * where heading=0° is North, pitch=0° is flat face-up, pitch=90° is upright portrait,
     * pitch=-45° is top tilted downward, pitch=115° is camera tilted skyward past vertical.
     */
    private fun buildEnuRotationMatrix(headingDeg: Double, pitchDeg: Double, rollDeg: Double): FloatArray {
        val h = Math.toRadians(headingDeg)
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)

        val ch = cos(-h); val sh = sin(-h)
        val cp = cos(p); val sp = sin(p)
        val cr = cos(r); val sr = sin(r)

        // Rz(-h)
        val rz = doubleArrayOf(
            ch, -sh, 0.0,
            sh, ch, 0.0,
            0.0, 0.0, 1.0
        )
        // Rx(p)
        val rx = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, cp, -sp,
            0.0, sp, cp
        )
        // Ry(r)
        val ry = doubleArrayOf(
            cr, 0.0, sr,
            0.0, 1.0, 0.0,
            -sr, 0.0, cr
        )

        fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val out = DoubleArray(9)
            for (row in 0..2) {
                for (col in 0..2) {
                    out[row * 3 + col] =
                        a[row * 3 + 0] * b[0 * 3 + col] +
                        a[row * 3 + 1] * b[1 * 3 + col] +
                        a[row * 3 + 2] * b[2 * 3 + col]
                }
            }
            return out
        }

        val combined = mul(mul(rz, rx), ry)
        return FloatArray(9) { combined[it].toFloat() }
    }

    @Test
    fun qiblaMath_exhaustive3dSweepAcrossAllHeadingsPitchesAndRollsHasZeroFlipError() {
        val headings = listOf(0.0, 45.0, 90.0, 136.1, 180.0, 244.4, 270.0, 359.0)
        val handheldPitches = listOf(-45.0, -30.0, 0.0, 30.0, 45.0, 60.0)
        val uprightPitches = listOf(75.0, 90.0, 105.0, 115.0)
        val rolls = listOf(-25.0, 0.0, 25.0)

        // 1. Handheld / Flat Compass mode (including downward tilt -45° + bank roll ±25°): < 0.05° error everywhere
        for (heading in headings) {
            for (pitch in handheldPitches) {
                for (roll in rolls) {
                    val rot = buildEnuRotationMatrix(heading, pitch, roll)
                    val measured = QiblaMathUtils.extractHorizontalHeading(rot, isCameraMode = false)
                    val err = abs(QiblaMathUtils.calculateShortestAngleDelta(heading.toFloat(), measured))
                    assertTrue(
                        "Handheld Compass error too high at heading=$heading pitch=$pitch roll=$roll: measured=$measured err=$err",
                        err < 0.05f
                    )
                }
            }
        }

        // 2. Upright / Sky-tilted Compass & AR Camera modes (including steering-wheel screen roll ±35° around lens Z): < 0.05° error
        for (heading in headings) {
            for (pitch in uprightPitches) {
                val baseRot = buildEnuRotationMatrix(heading, pitch, 0.0)
                for (screenRollDeg in listOf(-35.0, 0.0, 35.0)) {
                    val sr = Math.toRadians(screenRollDeg)
                    val cs = cos(sr).toFloat(); val ss = sin(sr).toFloat()
                    // Multiply baseRot * Rz_device(screenRoll): rotates phone screen around optical axis (-Z)
                    val rot = FloatArray(9)
                    for (row in 0..2) {
                        rot[row * 3 + 0] = baseRot[row * 3 + 0] * cs + baseRot[row * 3 + 1] * ss
                        rot[row * 3 + 1] = -baseRot[row * 3 + 0] * ss + baseRot[row * 3 + 1] * cs
                        rot[row * 3 + 2] = baseRot[row * 3 + 2]
                    }
                    val arMeasured = QiblaMathUtils.extractHorizontalHeading(rot, isCameraMode = true)
                    val compassMeasured = QiblaMathUtils.extractHorizontalHeading(rot, isCameraMode = false)
                    val arErr = abs(QiblaMathUtils.calculateShortestAngleDelta(heading.toFloat(), arMeasured))
                    val compErr = abs(QiblaMathUtils.calculateShortestAngleDelta(heading.toFloat(), compassMeasured))
                    assertTrue(
                        "AR Camera error at heading=$heading pitch=$pitch screenRoll=$screenRollDeg: err=$arErr",
                        arErr < 0.05f
                    )
                    assertTrue(
                        "Upright Compass error at heading=$heading pitch=$pitch screenRoll=$screenRollDeg: err=$compErr",
                        compErr < 0.05f
                    )
                }
            }
        }

        // 3. Ceiling (pitch=180°) & Floor (pitch=0°) AR fallback must stay aligned to North with ZERO 180° flip
        assertEquals(0f, QiblaMathUtils.extractHorizontalHeading(buildEnuRotationMatrix(0.0, 0.0, 0.0), isCameraMode = true), 0.05f)
        assertEquals(0f, QiblaMathUtils.extractHorizontalHeading(buildEnuRotationMatrix(0.0, 180.0, 0.0), isCameraMode = true), 0.05f)

        // 4. Camera elevation check: upright (90°)=0° horizon, tilted up (120°)=+30° sky, tilted down (60°)=-30° floor
        assertEquals(0f, QiblaMathUtils.extractCameraElevationDegrees(buildEnuRotationMatrix(136.1, 90.0, 0.0)), 0.05f)
        assertEquals(30f, QiblaMathUtils.extractCameraElevationDegrees(buildEnuRotationMatrix(136.1, 120.0, 0.0)), 0.05f)
        assertEquals(-30f, QiblaMathUtils.extractCameraElevationDegrees(buildEnuRotationMatrix(136.1, 60.0, 0.0)), 0.05f)
    }

    @Test
    fun qiblaMath_greatCircleBearingDistancesContinuityAndReleaseTagVerified() {
        // Cairo -> Makkah (~136.1°, ~1286 km)
        val cairo = QiblaMathUtils.calculateQiblaBearing(30.0444, 31.2357)
        val cairoDist = QiblaMathUtils.calculateDistanceToKaabaKm(30.0444, 31.2357)
        assertTrue("Cairo Qibla should be ~136° but got $cairo", abs(cairo - 136.1f) < 1.0f)
        assertTrue("Cairo distance should be ~1286 km but got $cairoDist", abs(cairoDist - 1286.0) < 15.0)

        // Riyadh -> Makkah (~244.4°, ~793 km)
        val riyadh = QiblaMathUtils.calculateQiblaBearing(24.7136, 46.6753)
        val riyadhDist = QiblaMathUtils.calculateDistanceToKaabaKm(24.7136, 46.6753)
        assertTrue("Riyadh Qibla should be ~244.4° but got $riyadh", abs(riyadh - 244.4f) < 1.0f)
        assertTrue("Riyadh distance should be ~793 km but got $riyadhDist", abs(riyadhDist - 793.0) < 15.0)

        // London -> Makkah (~119.0°), New York (~58.5°), Jakarta (~295.1°), Tokyo (~293.0°)
        assertTrue(abs(QiblaMathUtils.calculateQiblaBearing(51.5074, -0.1278) - 119.0f) < 1.0f)
        assertTrue(abs(QiblaMathUtils.calculateQiblaBearing(40.7128, -74.0060) - 58.5f) < 1.0f)
        assertTrue(abs(QiblaMathUtils.calculateQiblaBearing(-6.2088, 106.8456) - 295.1f) < 1.0f)
        assertTrue(abs(QiblaMathUtils.calculateQiblaBearing(35.6762, 139.6503) - 293.0f) < 1.0f)

        // Shortest delta wrap-around and alignment lock (±2.5°)
        assertEquals(2f, QiblaMathUtils.calculateShortestAngleDelta(359f, 1f), 0.01f)
        assertEquals(-2f, QiblaMathUtils.calculateShortestAngleDelta(1f, 359f), 0.01f)
        assertTrue(QiblaMathUtils.isAlignedWithQibla(134.0f, 136.1f, toleranceDegrees = 2.5f))
        assertFalse(QiblaMathUtils.isAlignedWithQibla(133.0f, 136.1f, toleranceDegrees = 2.5f))

        // Magnetic field interference detector
        assertFalse(QiblaMathUtils.isMagneticFieldInterfered(45f))
        assertTrue(QiblaMathUtils.isMagneticFieldInterfered(120f))
        assertTrue(QiblaMathUtils.isMagneticFieldInterfered(10f))

        // Verify Gemma 3 release tag targets ai-v1.0.3
        assertTrue(ModelDownloadManager.DEFAULT_MANIFEST_URL.contains("/ai-v1.0.3/"))
        assertTrue(ModelDownloadManager.DEFAULT_DIRECT_MODEL_URL.contains("/ai-v1.0.3/"))
    }

    // =========================================================================
    // 5. Conversational Greeting, Completion, Sadness Counseling & Multi-Turn Tests
    // =========================================================================

    @Test
    fun nabdRouter_handlesIslamicGreetingsWithoutReturningRandomDhikrCard() = runBlocking {
        val offlineProbe = object : ConnectivityProbe { override fun isOnline(): Boolean = false }
        val router = NabdAiRouter(connectivityProbe = offlineProbe)

        for (greeting in listOf(
            "السلام عليكم",
            "السلام عليكم و رحمة الله و بركاته",
            "صباح الخير",
            "ازيك عامل ايه",
            "شكرا جزيلا"
        )) {
            val reply = router.routeQuery(greeting, sampleCategories, emptyMap())
            assertEquals("Expected CONVERSATIONAL for '$greeting'", NabdAiSourceType.CONVERSATIONAL, reply.sourceType)
            assertNull("Must NOT attach a random dhikr card for '$greeting'", reply.linkedCategoryId)
        }

        // Mixed greeting + actual dhikr request MUST both greet AND answer the request
        val mixed = router.routeQuery("السلام عليكم عايز أذكار الصباح", sampleCategories, emptyMap())
        assertEquals(NabdAiSourceType.LOCAL_ADHKAR, mixed.sourceType)
        assertTrue(mixed.answerText.contains("وعليكم السلام"))
        assertEquals("morning_azkar", mixed.linkedCategoryId)
    }

    @Test
    fun nabdRouter_completesPartialAdhkarAndEmpathizesWithSadnessAndFollowUpCounts() = runBlocking {
        val offlineProbe = object : ConnectivityProbe { override fun isOnline(): Boolean = false }
        val router = NabdAiRouter(connectivityProbe = offlineProbe)

        // 1. User's partial completion query: "لا اله الا العظيم الحليم لا اله الا الله و كمل"
        val completion = router.routeQuery(
            "اذكار الحزن فيها لا اله الا العظيم الحليم لا اله الا الله و كمل انت عارفه",
            sampleCategories,
            emptyMap()
        )
        assertEquals(NabdAiSourceType.LOCAL_ADHKAR, completion.sourceType)
        assertEquals(1706, completion.linkedDhikrId)
        assertTrue(completion.answerText.contains("رَبُّ الْعَرْشِ الْكَرِيمِ"))
        assertTrue(completion.answerText.contains("6346"))

        // 2. Sadness queries ("انا زعلان" and nuanced "زعلان في حاجة معينة") must give empathetic top adhkar & open index 0 (NOT #3)
        val sad = router.routeQuery("انا زعلان", sampleCategories, emptyMap())
        assertEquals(NabdAiSourceType.LOCAL_ADHKAR, sad.sourceType)
        assertEquals("worry_azkar", sad.linkedCategoryId)
        assertEquals(0, sad.linkedDhikrIndex)
        assertTrue(sad.answerText.contains("الْعَظِيمُ الْحَلِيمُ"))
        assertTrue(sad.answerText.contains("اللَّهُمَّ إِنِّي عَبْدُكَ"))

        val specificSad = router.routeQuery("انا زعلان في حاجة معينة حصلتلي", sampleCategories, emptyMap())
        assertEquals(NabdAiSourceType.LOCAL_ADHKAR, specificSad.sourceType)
        assertTrue(specificSad.answerText.contains("هذا الأمر تحديداً"))

        // 3. Multi-turn follow-up: "اقوله كام مره؟" reuses previous active Dhikr context accurately
        val ctx = AiConversationContext(
            lastCategoryId = completion.linkedCategoryId,
            lastCategoryName = "دعاء الهم والحزن",
            lastDhikrId = completion.linkedDhikrId,
            lastDhikrIndex = completion.linkedDhikrIndex,
            lastDhikrText = completion.matchedDhikrText,
            lastSource = completion.matchedSource,
            lastHadithNumber = completion.matchedHadithNumber,
            lastRepetition = completion.matchedRepetition
        )
        val countReply = router.routeQuery("أقوله كام مرة؟", sampleCategories, emptyMap(), ctx)
        assertEquals(NabdAiSourceType.LOCAL_ADHKAR, countReply.sourceType)
        assertTrue(countReply.answerText.contains("الْعَظِيمُ الْحَلِيمُ"))
        assertTrue(countReply.answerText.contains("3 مرات") || countReply.answerText.contains("مرة"))
    }
}
