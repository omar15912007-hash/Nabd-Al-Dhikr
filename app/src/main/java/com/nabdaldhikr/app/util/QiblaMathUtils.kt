package com.nabdaldhikr.app.util

import android.hardware.GeomagneticField
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * دقة رياضية بحتة لحساب اتجاه القبلة، والمسافة إلى مكة المكرمة، وتصحيح الشمال الحقيقي،
 * وتنعيم دوران الإبرة عبر أقصر مسار زاوي.
 */
object QiblaMathUtils {

    /** الإحداثيات الجغرافية الدقيقة للكعبة المشرفة في مكة المكرمة */
    const val KAABA_LATITUDE = 21.4224779
    const val KAABA_LONGITUDE = 39.8261666

    /** نصف قطر الكرة الأرضية التقريبي بالكيلومترات (Mean Earth Radius) */
    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * حساب زاوية القبلة (Initial Bearing / Forward Azimuth) عبر الدائرة العظمى (Great-Circle):
     * θ = atan2(sin(Δλ) * cos(φ2), cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ))
     *
     * @param userLat خط عرض المستخدم بالدرجات العادية
     * @param userLon خط طول المستخدم بالدرجات العادية
     * @return زاوية اتجاه القبلة بالنسبة للشمال الحقيقي محصورة بين [0.0, 360.0)
     */
    fun calculateQiblaBearing(userLat: Double, userLon: Double): Float {
        val phi1 = Math.toRadians(userLat)
        val phi2 = Math.toRadians(KAABA_LATITUDE)
        val deltaLambda = Math.toRadians(KAABA_LONGITUDE - userLon)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)

        val initialBearingRad = atan2(y, x)
        val initialBearingDeg = Math.toDegrees(initialBearingRad)

        return normalize360(initialBearingDeg).toFloat()
    }

    /**
     * حساب المسافة السطحية المباشرة إلى الكعبة المشرفة بالكيلومترات باستخدام صيغة هافرسين (Haversine Formula):
     * a = sin²(Δφ/2) + cos(φ1) * cos(φ2) * sin²(Δλ/2)
     * c = 2 * asin(sqrt(a))
     * d = R * c
     *
     * @param userLat خط عرض المستخدم
     * @param userLon خط طول المستخدم
     * @return المسافة بالكيلومتر
     */
    fun calculateDistanceToKaabaKm(userLat: Double, userLon: Double): Double {
        val dLat = Math.toRadians(KAABA_LATITUDE - userLat)
        val dLon = Math.toRadians(KAABA_LONGITUDE - userLon)
        val lat1 = Math.toRadians(userLat)
        val lat2 = Math.toRadians(KAABA_LATITUDE)

        val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * asin(sqrt(a))

        return EARTH_RADIUS_KM * c
    }

    /**
     * تصحيح الانحراف المغناطيسي للأرض للوصول إلى الشمال الجغرافي الحقيقي (Geographic True North):
     * يعتمد على نموذج WMM عبر كلاس GeomagneticField الرسمي من أندرويد.
     *
     * @param magneticHeading زاوية البوصلة المغناطيسية المقروءة من الحساسات [0, 360)
     * @param latitude خط عرض المستخدم
     * @param longitude خط طول المستخدم
     * @param altitudeMeters الارتفاع عن سطح البحر بالأمتار (افتراضياً 0.0)
     * @param timeMillis التوقيت الحالي بالمللي ثانية
     * @return الزاوية المصححة للشمال الحقيقي [0, 360)
     */
    fun correctToTrueNorth(
        magneticHeading: Float,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double = 0.0,
        timeMillis: Long = System.currentTimeMillis()
    ): Float {
        return try {
            val geomagneticField = GeomagneticField(
                latitude.toFloat(),
                longitude.toFloat(),
                altitudeMeters.toFloat(),
                timeMillis
            )
            val declination = geomagneticField.declination // الانحراف بالدرجات (موجب شرقاً، سالب غرباً)
            normalize360((magneticHeading + declination).toDouble()).toFloat()
        } catch (_: Exception) {
            // في حال تعذر الحساب يظل الاتجاه المغناطيسي كما هو كخيار آمن
            normalize360(magneticHeading.toDouble()).toFloat()
        }
    }

    /**
     * حساب أقصر فارق زاوي بين زاويتين في النطاق [-180, 180]:
     * يمنع الدوران العكسي المزعج عند الانتقال بين 359° و 1°
     *
     * @param fromAngle الزاوية الحالية
     * @param toAngle الزاوية المستهدفة
     * @return الفارق الزاوي بالأقصر اتجاه (-180° إلى +180°)
     */
    fun calculateShortestAngleDelta(fromAngle: Float, toAngle: Float): Float {
        var diff = (toAngle - fromAngle) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    /**
     * حساب زاوية تراكمية مستمرة (Continuous Angle) لضمان سلاسة حركة الرسوم المتحركة (60fps animation):
     * تمنع الإبرة من الدوران العكسي بمقدار 358 درجة عند عبور نقطة الصفر (360° ↔ 0°).
     *
     * @param previousContinuousAngle الزاوية السابقة المستمرة (قد تكون سالبة أو تتجاوز 360)
     * @param newTargetAngle الزاوية الجديدة في المجال [0, 360)
     * @return زاوية مستمرة جديدة تضمن أقصر مسار تحريك
     */
    fun calculateContinuousAngle(
        previousContinuousAngle: Float,
        newTargetAngle: Float
    ): Float {
        val currentNormalized = normalize360(previousContinuousAngle.toDouble()).toFloat()
        val delta = calculateShortestAngleDelta(currentNormalized, newTargetAngle)
        return previousContinuousAngle + delta
    }

    /**
     * حساب زاوية توجيه الإبرة بالنسبة لأعلى شاشة الهاتف (12 O'Clock):
     * NeedleAngle = QiblaBearing - DeviceHeading (True North)
     *
     * @param deviceHeadingTrueNorth اتجاه الهاتف بالنسبة للشمال الحقيقي [0, 360)
     * @param qiblaBearing اتجاه القبلة بالنسبة للشمال الحقيقي [0, 360)
     * @return زاوية الإبرة بالنسبة لأعلى الهاتف [0, 360)
     */
    fun calculateNeedleAngle(
        deviceHeadingTrueNorth: Float,
        qiblaBearing: Float
    ): Float {
        return normalize360((qiblaBearing - deviceHeadingTrueNorth).toDouble()).toFloat()
    }

    /**
     * مرشح تمرير منخفض زاوي (Low-Pass Filter) لتنعيم قراءات البوصلة الحركية
     * يحل مشكلة تشوه المتوسط الحسابي بالقرب من نقطة 0/360 بالاعتماد على المكونات المثلثية (Sin/Cos).
     *
     * @param currentAngle الزاوية الجديدة المقروءة
     * @param previousAngle الزاوية المنعمة السابقة
     * @param alpha معامل الترشيح (0.15f يعطي توازناً مثالياً بين سرعة الاستجابة ومنع الارتعاش)
     * @return زاوية منعمة ثابتة
     */
    fun lowPassFilter(
        currentAngle: Float,
        previousAngle: Float,
        alpha: Float = 0.15f
    ): Float {
        val currentRad = Math.toRadians(currentAngle.toDouble())
        val prevRad = Math.toRadians(previousAngle.toDouble())

        val sinVal = (1.0 - alpha) * sin(prevRad) + alpha * sin(currentRad)
        val cosVal = (1.0 - alpha) * cos(prevRad) + alpha * cos(currentRad)

        val smoothedDeg = Math.toDegrees(atan2(sinVal, cosVal))
        return normalize360(smoothedDeg).toFloat()
    }

    /**
     * التحقق مما إذا كان اتجاه الهاتف الحالي متطابقاً مع القبلة ضمن هامش سماحية محدد (±3 درجات).
     *
     * @param currentHeading اتجاه الهاتف
     * @param qiblaBearing اتجاه القبلة
     * @param toleranceDegrees هامش الخطأ المسموح به (افتراضياً 3.0 درجات)
     * @return true إذا كان الاتجاه نحو الكعبة بدقة
     */
    fun isAlignedWithQibla(
        currentHeading: Float,
        qiblaBearing: Float,
        toleranceDegrees: Float = 3.0f
    ): Boolean {
        val delta = abs(calculateShortestAngleDelta(currentHeading, qiblaBearing))
        return delta <= toleranceDegrees
    }

    /**
     * تطبيع أي قيمة بالدرجات لتكون دائماً محصورة في النطاق الرياضي القياسي [0.0, 360.0).
     */
    fun normalize360(degrees: Double): Double {
        return (degrees % 360.0 + 360.0) % 360.0
    }

    /**
     * استخراج زاوية الاتجاه المغناطيسي الحقيقي [0, 360) مباشرةً من مصفوفة الدوران العالمية (ENU)
     * بدون أي قفز 180° أو Gimbal Lock:
     *
     * في إحداثيات أندرويد (V_world = R * V_device، حيث الصف 0 = شرق، الصف 1 = شمال، الصف 2 = أعلى):
     * - الحافة العلوية للهاتف (+Y_device) في المستوى الأفقي = (R[1], R[4])
     * - عدسة الكاميرا الخلفية (-Z_device) في المستوى الأفقي = (-R[2], -R[5])
     *
     * @param rotationMatrix مصفوفة دوران 3x3 (طول 9) من TYPE_ROTATION_VECTOR أو Accel+Mag
     * @param isCameraMode true في وضع الكاميرا الخلفية (AR)، false في وضع البوصلة الأفقية
     * @return زاوية الاتجاه باتجاه الشمال المغناطيسي [0, 360)
     */
    fun extractHorizontalHeading(
        rotationMatrix: FloatArray,
        isCameraMode: Boolean
    ): Float {
        if (rotationMatrix.size < 9) return 0f

        // في إحداثيات أندرويد العالمية ENU (V_world = R * V_device):
        // - الحافة العلوية للهاتف (+Y_device) في المستوى الأفقي = (R[1], R[4])
        // - المحور البصري للعدسة الخلفية (-Z_device) في المستوى الأفقي = (-R[2], -R[5])
        // - R[8] = (+Z_device)·Up (موجب عندما تكون الشاشة للأعلى، ≈0 عند الوقوف العمودي، سالب عند الإمالة للسماء)
        // - R[7] = (+Y_device)·Up (موجب عندما تكون الحافة العلوية للأعلى)
        val topEast = rotationMatrix[1].toDouble()
        val topNorth = rotationMatrix[4].toDouble()
        val camEast = -rotationMatrix[2].toDouble()
        val camNorth = -rotationMatrix[5].toDouble()

        val hTop = hypot(topEast, topNorth)
        val hCam = hypot(camEast, camNorth)
        val screenUp = rotationMatrix[8].toDouble()
        val topUp = rotationMatrix[7].toDouble()

        val east: Double
        val north: Double

        if (isCameraMode) {
            // في وضع الكاميرا (AR): نعتمد مباشرةً على المحور البصري للعدسة الخلفية (-Z_device)
            // وإذا وُجهت العدسة عمودياً تماماً للأرض (screenUp > 0) نستخدم (+Y)، أو للسقف (screenUp < 0) نستخدم (-Y)
            if (hCam >= 0.15) {
                east = camEast
                north = camNorth
            } else if (hTop >= 0.15) {
                val sign = if (screenUp >= 0.0) 1.0 else -1.0
                east = sign * topEast
                north = sign * topNorth
            } else {
                east = -rotationMatrix[3].toDouble()
                north = rotationMatrix[0].toDouble()
            }
        } else {
            // في وضع البوصلة:
            // - عندما تكون الشاشة مسطحة أو مائلة باليد حتى ~68° (screenUp >= 0.35): نستخدم الحافة العلوية (+Y)
            //   لتجنب أي انحراف ناتج عن إمالة الهاتف جانبياً (Roll) مع دقة 0.00° عبر كامل النطاق [-68°..+68°].
            // - وعند رفع الهاتف عمودياً أو إمالته للسماء (screenUp < 0.35): ننتقل بسلاسة تامة بدون أي قفزة
            //   إلى المحور البصري الأمامي (-Z_device) مع دقة 0.00° ضد دوران الشاشة الجانبي.
            if (screenUp >= 0.35 && hTop >= 0.15) {
                east = topEast
                north = topNorth
            } else if (topUp >= 0.0 && hCam >= 0.15) {
                east = camEast
                north = camNorth
            } else if (hTop >= 0.15) {
                east = topEast
                north = topNorth
            } else if (hCam >= 0.15) {
                east = camEast
                north = camNorth
            } else {
                return 0f
            }
        }

        val azimuthDeg = Math.toDegrees(atan2(east, north))
        return normalize360(azimuthDeg).toFloat()
    }

    /**
     * استخراج زاوية ارتفاع عدسة الكاميرا الخلفية (-Z_device) عن الأفق بالدرجات [-90, +90]:
     * 0° = موجهة تماماً للأفق، +90° = للسماء، -90° = للأرض.
     */
    fun extractCameraElevationDegrees(rotationMatrix: FloatArray): Float {
        if (rotationMatrix.size < 9) return 0f
        val camUp = (-rotationMatrix[8]).coerceIn(-1f, 1f).toDouble()
        return Math.toDegrees(asin(camUp)).toFloat()
    }

    /**
     * كشف التداخل المغناطيسي (معادن، سماعات، مغناطيس حافظات الهاتف):
     * شدة المجال المغناطيسي الطبيعي للأرض تتراوح بين 25 و 65 ميكروتسلا (µT).
     */
    fun isMagneticFieldInterfered(magnitudeMicroTesla: Float): Boolean {
        return magnitudeMicroTesla < 20f || magnitudeMicroTesla > 75f
    }
}
