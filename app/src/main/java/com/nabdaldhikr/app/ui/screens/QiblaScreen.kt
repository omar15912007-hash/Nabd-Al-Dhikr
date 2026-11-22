package com.nabdaldhikr.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.nabdaldhikr.app.util.QiblaMathUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.nabdaldhikr.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

private const val QIBLA_PREFS_NAME = "nabd_qibla_cache"
private const val KEY_LAST_LAT = "last_lat"
private const val KEY_LAST_LON = "last_lon"
private const val KEY_LAST_ALT = "last_alt"
private const val KEY_LAST_CITY = "last_city"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QiblaScreen(
    navController: NavController,
    vibrationEnabled: Boolean = true
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val qiblaPrefs = remember(context) {
        context.getSharedPreferences(QIBLA_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // استرجاع آخر موقع حقيقي محفوظ فوراً (مفيد داخل المساجد أو بدون إنترنت، ويُحدّث تلقائياً فور التقاط GPS)
    val cachedLat = remember(qiblaPrefs) {
        if (qiblaPrefs.contains(KEY_LAST_LAT)) java.lang.Double.longBitsToDouble(qiblaPrefs.getLong(KEY_LAST_LAT, 0L)) else Double.NaN
    }
    val cachedLon = remember(qiblaPrefs) {
        if (qiblaPrefs.contains(KEY_LAST_LON)) java.lang.Double.longBitsToDouble(qiblaPrefs.getLong(KEY_LAST_LON, 0L)) else Double.NaN
    }
    val cachedAlt = remember(qiblaPrefs) {
        if (qiblaPrefs.contains(KEY_LAST_ALT)) java.lang.Double.longBitsToDouble(qiblaPrefs.getLong(KEY_LAST_ALT, 0L)) else 0.0
    }
    val cachedCity = remember(qiblaPrefs) { qiblaPrefs.getString(KEY_LAST_CITY, null) }
    val hasCachedCoords = !cachedLat.isNaN() && !cachedLon.isNaN()

    // حالة الموقع الجغرافي الدقيق (بدون عرض قبلة وهمية قبل التقاط موقع حقيقي)
    var userLat by remember { mutableDoubleStateOf(if (hasCachedCoords) cachedLat else 0.0) }
    var userLon by remember { mutableDoubleStateOf(if (hasCachedCoords) cachedLon else 0.0) }
    var userAlt by remember { mutableDoubleStateOf(if (hasCachedCoords) cachedAlt else 0.0) }
    var hasValidLocation by remember { mutableStateOf(hasCachedCoords) }
    var accuracyMeters by remember { mutableFloatStateOf(0f) }
    var locationSource by remember {
        mutableStateOf(if (hasCachedCoords) "آخر موقع محفوظ (جاري التحديث...)" else "جاري تحديد موقعك الدقيق...")
    }
    var cityName by remember { mutableStateOf(cachedCity ?: "تحديد الموقع...") }
    var isGpsEnabled by remember { mutableStateOf(true) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    // حالة وضع الكاميرا (AR بالكاميرا افتراضياً فور منح الصلاحية)
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isArMode by remember { mutableStateOf(hasCameraPermission) }
    var cameraHorizontalFov by remember { mutableFloatStateOf(55f) }

    // قراءات الحساسات (البوصلة الدقيقة + ارتفاع عدسة الكاميرا + كشف التداخل المغناطيسي)
    var smoothAzimuth by remember { mutableFloatStateOf(0f) }
    var hasAzimuthFix by remember { mutableStateOf(false) }
    var smoothElevation by remember { mutableFloatStateOf(0f) }
    var smoothPitch by remember { mutableFloatStateOf(0f) }
    var smoothRoll by remember { mutableFloatStateOf(0f) }
    var sensorAccuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_HIGH) }
    var isMagneticInterfered by remember { mutableStateOf(false) }
    var hasSensor by remember { mutableStateOf(true) }

    // حساب الانحراف المغناطيسي مرة واحدة عند تحديث الموقع (صفر تخصيص داخل 60fps)
    val magneticDeclination = remember(userLat, userLon, userAlt, hasValidLocation) {
        if (!hasValidLocation) 0f
        else try {
            GeomagneticField(
                userLat.toFloat(),
                userLon.toFloat(),
                userAlt.toFloat(),
                System.currentTimeMillis()
            ).declination
        } catch (_: Exception) {
            0f
        }
    }

    // حساب زاوية القبلة والمسافة إلى الكعبة المشرفة
    val qiblaBearing = remember(userLat, userLon, hasValidLocation) {
        if (hasValidLocation) QiblaMathUtils.calculateQiblaBearing(userLat, userLon) else 0f
    }
    val distanceKm = remember(userLat, userLon, hasValidLocation) {
        if (hasValidLocation) QiblaMathUtils.calculateDistanceToKaabaKm(userLat, userLon) else 0.0
    }

    // فرق الزاوية الدائري الأقصر (-180 إلى +180)
    // موجب = القبلة على اليمين، سالب = القبلة على اليسار
    val angleDelta = remember(smoothAzimuth, qiblaBearing) {
        QiblaMathUtils.calculateShortestAngleDelta(smoothAzimuth, qiblaBearing)
    }
    val absDelta = abs(angleDelta)
    val isAligned = hasValidLocation && hasAzimuthFix && absDelta <= 2.5f
    val needsCalibration = isMagneticInterfered ||
        sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
        sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE

    // اهتزاز عند التطابق مع اتجاه القبلة
    var wasAligned by remember { mutableStateOf(false) }
    LaunchedEffect(isAligned) {
        if (isAligned && !wasAligned && vibrationEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        wasAligned = isAligned
    }

    // جلب اسم المدينة عبر الإنترنت (Geocoder أو IP Fallback)
    fun resolvePlaceName(lat: Double, lon: Double) {
        scope.launch(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val geocoder = Geocoder(context, Locale("ar"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: ""
                    val country = addr.countryName ?: ""
                    val label = listOf(locality, country).filter { it.isNotBlank() }.joinToString("، ")
                    if (label.isNotBlank()) {
                        qiblaPrefs.edit().putString(KEY_LAST_CITY, label).apply()
                        withContext(Dispatchers.Main) { cityName = label }
                        return@launch
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // تحديد الموقع عبر الإنترنت في حال ضعف الـ GPS داخل المباني
    fun fetchNetworkIpLocationIfNeeded() {
        scope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("https://ipwho.is/?lang=ar").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val lat = json.optDouble("latitude", Double.NaN)
                        val lon = json.optDouble("longitude", Double.NaN)
                        val city = json.optString("city", "")
                        val country = json.optString("country", "")
                        if (!lat.isNaN() && !lon.isNaN()) {
                            withContext(Dispatchers.Main) {
                                if (!hasValidLocation || accuracyMeters == 0f) {
                                    userLat = lat
                                    userLon = lon
                                    hasValidLocation = true
                                    locationSource = "تحديد عبر الشبكة"
                                    val label = listOf(city, country).filter { it.isNotBlank() }.joinToString("، ")
                                    if (label.isNotBlank()) cityName = label
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (cityName == "تحديد الموقع...") {
                        cityName = String.format(Locale.US, "%.3f° N, %.3f° E", userLat, userLon)
                    }
                }
            }
        }
    }

    val fusedLocationClient = remember(context) { LocationServices.getFusedLocationProviderClient(context) }

    fun applyPreciseLocation(loc: Location, labelPrefix: String) {
        userLat = loc.latitude
        userLon = loc.longitude
        userAlt = loc.altitude
        hasValidLocation = true
        accuracyMeters = loc.accuracy
        locationSource = "$labelPrefix (±${loc.accuracy.roundToInt()} م)"
        qiblaPrefs.edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(loc.latitude))
            .putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(loc.longitude))
            .putLong(KEY_LAST_ALT, java.lang.Double.doubleToRawLongBits(loc.altitude))
            .apply()
        if (cityName == "تحديد الموقع...") {
            cityName = String.format(Locale.US, "%.4f°, %.4f°", userLat, userLon)
        }
        resolvePlaceName(loc.latitude, loc.longitude)
    }

    // تحديث الموقع الدقيق عبر FusedLocationProviderClient (PRIORITY_HIGH_ACCURACY) + LocationManager
    @SuppressLint("MissingPermission")
    fun refreshLocation() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        isGpsEnabled = gpsOn || netOn

        if (!hasLocationPermission) {
            fetchNetworkIpLocationIfNeeded()
            return
        }

        // 1. طلب قراءة فورية عالية الدقة من Google FusedLocationProviderClient
        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { fresh ->
                if (fresh != null) {
                    applyPreciseLocation(fresh, "GPS عالي الدقة")
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                        if (cached != null) {
                            applyPreciseLocation(cached, "GPS دقيق")
                        } else if (!hasValidLocation) {
                            fetchNetworkIpLocationIfNeeded()
                        }
                    }
                }
            }
            .addOnFailureListener {
                if (!hasValidLocation) fetchNetworkIpLocationIfNeeded()
            }

        // 2. قراءة فورية من أفضل آخر موقع متاح لتسريع العرض الأولي
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var bestLoc: Location? = null
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (bestLoc == null || loc.accuracy < bestLoc.accuracy || loc.time > bestLoc.time + 60_000) {
                    bestLoc = loc
                }
            } catch (_: Exception) {}
        }

        if (bestLoc != null && !hasValidLocation) {
            val tag = if (bestLoc.provider == LocationManager.GPS_PROVIDER) "GPS دقيق" else "موقع الشبكة"
            applyPreciseLocation(bestLoc, tag)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ||
            perms.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            hasLocationPermission = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            refreshLocation()
        }
        if (perms.containsKey(Manifest.permission.CAMERA)) {
            val camGranted = perms[Manifest.permission.CAMERA] == true
            hasCameraPermission = camGranted
            if (camGranted) isArMode = true
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) isArMode = true
    }

    // طلب الصلاحيات دفعة واحدة عند فتح الشاشة (الموقع + الكاميرا) لمنع إلغاء أندرويد للطلب الثاني
    LaunchedEffect(Unit) {
        val missingPermissions = buildList {
            if (!hasLocationPermission) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!hasCameraPermission) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (hasLocationPermission) {
            refreshLocation()
        }
        if (missingPermissions.isNotEmpty()) {
            locationPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    // الاستماع المستمر لتحديثات الموقع (GPS + Network)
    DisposableEffect(hasLocationPermission) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val tag = if (location.provider == LocationManager.GPS_PROVIDER) "GPS مباشر" else "الشبكة المباشرة"
                applyPreciseLocation(location, tag)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) { isGpsEnabled = true }
            override fun onProviderDisabled(provider: String) {}
        }

        if (hasLocationPermission) {
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 1f, listener, Looper.getMainLooper())
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, listener, Looper.getMainLooper())
                }
            } catch (_: SecurityException) {}
        }

        onDispose {
            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {}
        }
    }

    // الاستماع لحساسات الاتجاه والمجال المغناطيسي مع إسقاط متجهي عالمي (ENU) دقيق 100% بدون أي انقلاب 180°
    // وكشف التداخل المغناطيسي (20..75 µT) وتصحيح الشمال الجغرافي الحقيقي (True North)
    val latestDeclination by rememberUpdatedState(magneticDeclination)
    val latestArMode by rememberUpdatedState(isArMode)

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (rotationSensor == null && (accelSensor == null || magSensor == null)) {
            hasSensor = false
        }

        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var hasGravity = false
        var hasMag = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                var matrixReady = false
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        if (event.accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT) {
                            sensorAccuracy = event.accuracy
                        }
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        matrixReady = true
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        lowPassFilter(event.values, gravity, 0.15f)
                        hasGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        if (event.accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT) {
                            sensorAccuracy = event.accuracy
                        }
                        // كشف فوري للتداخل المغناطيسي (معادن أو مغناطيس جراب الهاتف)
                        val bx = event.values[0]
                        val by = event.values[1]
                        val bz = event.values[2]
                        val fieldStrength = sqrt(bx * bx + by * by + bz * bz)
                        isMagneticInterfered = QiblaMathUtils.isMagneticFieldInterfered(fieldStrength)

                        lowPassFilter(event.values, geomagnetic, 0.15f)
                        hasMag = true
                    }
                }

                if (!matrixReady && rotationSensor == null && hasGravity && hasMag) {
                    matrixReady = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
                }

                if (matrixReady) {
                    // استخراج زاوية الاتجاه الحقيقية عبر الإسقاط المتجهي المباشر (عدسة الكاميرا الخلفية -Z في AR)
                    val magAzimuthDeg = QiblaMathUtils.extractHorizontalHeading(
                        rotationMatrix = rotationMatrix,
                        isCameraMode = latestArMode
                    )
                    val elevationDeg = QiblaMathUtils.extractCameraElevationDegrees(rotationMatrix)

                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                    val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

                    val trueAzimuth = QiblaMathUtils.normalize360(
                        (magAzimuthDeg + latestDeclination).toDouble()
                    ).toFloat()

                    // تنعيم دائري فائق السلاسة عبر Sin/Cos مع تثبيت أول قراءة مباشرة
                    if (!hasAzimuthFix) {
                        smoothAzimuth = trueAzimuth
                        smoothElevation = elevationDeg
                        smoothPitch = pitchDeg
                        smoothRoll = rollDeg
                        hasAzimuthFix = true
                    } else {
                        val diff = QiblaMathUtils.calculateShortestAngleDelta(smoothAzimuth, trueAzimuth)
                        smoothAzimuth = QiblaMathUtils.normalize360((smoothAzimuth + diff * 0.22f).toDouble()).toFloat()
                        smoothElevation += (elevationDeg - smoothElevation) * 0.22f
                        smoothPitch += (pitchDeg - smoothPitch) * 0.18f
                        smoothRoll += (rollDeg - smoothRoll) * 0.18f
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD || sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    sensorAccuracy = accuracy
                }
            }
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            // تسجيل المغناطيسية بشكل خفيف بجانب Rotation Vector لكشف التداخل المغناطيسي بدقة
            if (magSensor != null) {
                sensorManager.registerListener(listener, magSensor, SensorManager.SENSOR_DELAY_UI)
            }
        } else if (accelSensor != null && magSensor != null) {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(listener, magSensor, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    // نبض الهالة الذهبية/الخضراء حول الكعبة
    val infiniteTransition = rememberInfiniteTransition(label = "QiblaPulse")
    val pulseRadiusFraction by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseRadius"
    )
    val arrowPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ArrowPhase"
    )

    val activeAccentColor by animateColorAsState(
        targetValue = if (isAligned) CompletedGreen else PrimaryGold,
        animationSpec = tween(350),
        label = "AccentColor"
    )

    // كائنات الرسم النصية المحفوظة في الذاكرة لتجنب أي تخصيص أثناء الرسم (Clean Code / Zero Allocation)
    val degreePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val cardinalPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#C9A24D")
            textSize = 38f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "محدد القبلة الذكي",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = PrimaryGold
                        )
                        Text(
                            text = cityName,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshLocation() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "تحديث الموقع",
                            tint = PrimaryGold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderBg)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenGradient)
                .padding(padding)
        ) {
            // 1. خلفية الكاميرا المباشرة عند تفعيل وضع الواقع المعزز (AR مثل Google Qibla Finder)
            if (isArMode && hasCameraPermission) {
                CameraPreviewSurface(
                    modifier = Modifier.fillMaxSize(),
                    onHorizontalFovMeasured = { fov -> cameraHorizontalFov = fov }
                )
                // طبقة تظليل خفيفة لإبراز عناصر الواقع المعزز بوضوح
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                )
            }

            // 2. طبقة الرسم التفاعلي (شريط الأفق العلوي + بوابة الكعبة العائمة + خط التوجيه + رادار Qibla Finder السفلي)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val centerX = w / 2f
                val centerY = h * 0.44f

                // أ) شريط الأفق العلوي للدرجات (Horizon Compass Ribbon مثل Qibla Finder)
                drawHorizonRibbon(
                    width = w,
                    azimuth = smoothAzimuth,
                    qiblaBearing = qiblaBearing,
                    accentColor = activeAccentColor,
                    degreePaint = degreePaint
                )

                if (isArMode) {
                    // ب) وضع الكاميرا (AR): عدسة Qibla Finder التفاعلية مع بوابة الكعبة المثبتة على الأفق الحقيقي
                    drawArQiblaFinderOverlay(
                        centerX = centerX,
                        centerY = centerY,
                        angleDelta = angleDelta,
                        elevationDeg = smoothElevation,
                        horizontalFov = cameraHorizontalFov,
                        isAligned = isAligned,
                        pulseFraction = pulseRadiusFraction,
                        arrowPhase = arrowPhase,
                        accentColor = activeAccentColor,
                        distanceKm = distanceKm
                    )
                } else {
                    // ج) وضع البوصلة الذكية ثلاثية الأبعاد: حلقة Qibla Finder مع مسار التوجيه وميزان الاستواء
                    drawLuxuryQiblaCompass(
                        centerX = centerX,
                        centerY = centerY,
                        azimuth = smoothAzimuth,
                        qiblaBearing = qiblaBearing,
                        angleDelta = angleDelta,
                        pitch = smoothPitch,
                        roll = smoothRoll,
                        isAligned = isAligned,
                        pulseFraction = pulseRadiusFraction,
                        arrowPhase = arrowPhase,
                        accentColor = activeAccentColor,
                        cardinalPaint = cardinalPaint,
                        degreePaint = degreePaint
                    )
                }

                // د) الرادار السفلي ثلاثي الأبعاد (مثل الدائرة السفلية في Google Qibla Finder)
                if (isArMode) {
                    drawBottomPerspectiveRadar(
                        centerX = centerX,
                        centerY = h * 0.77f,
                        radius = min(w, h) * 0.14f,
                        azimuth = smoothAzimuth,
                        qiblaBearing = qiblaBearing,
                        accentColor = activeAccentColor
                    )
                }
            }

            // 3. شريط التبديل العلوي بين (البوصلة الذكية) و(الواقع المعزز AR بالكاميرا)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 52.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(HeaderBg.copy(alpha = 0.92f))
                        .border(1.dp, PrimaryGold.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModeChip(
                        title = "البوصلة الذكية",
                        icon = AppIcons.Explore,
                        selected = !isArMode,
                        onClick = { isArMode = false }
                    )
                    ModeChip(
                        title = "الكاميرا (AR)",
                        icon = AppIcons.CameraAlt,
                        selected = isArMode,
                        onClick = {
                            if (hasCameraPermission) {
                                isArMode = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // شارة التوجيه المباشر (يمين / يسار / متطابق)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isAligned) CompletedGreen.copy(alpha = 0.22f) else CardBg.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.65f))
                ) {
                    val guidanceText = when {
                        !hasSensor -> "جهازك لا يحتوي على حساس بوصلة مغناطيسي"
                        !hasValidLocation -> "جاري تحديد موقعك الجغرافي الدقيق..."
                        isAligned -> "أنت الآن باتجاه القبلة الصحيح تماماً (±2.5°)"
                        angleDelta > 0 -> "استدر لليمين بمقدار ${absDelta.roundToInt()}° نحو الكعبة"
                        else -> "استدر لليسار بمقدار ${absDelta.roundToInt()}° نحو الكعبة"
                    }
                    Text(
                        text = guidanceText,
                        color = if (isAligned) CompletedGreen else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp)
                    )
                }

                // تنبيه فوري عند وجود تداخل مغناطيسي أو حاجة لمعايرة البوصلة بحركة ∞
                if (needsCalibration && hasSensor) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xCC3B240C),
                        border = BorderStroke(1.dp, WarningAmber)
                    ) {
                        Text(
                            text = if (isMagneticInterfered) {
                                "⚠ تداخل مغناطيسي قريب! أبعد الهاتف عن المعادن والمغناطيس وحرّكه بشكل رقم 8 (∞)"
                            } else {
                                "⚠ دقة البوصلة منخفضة — حرّك الهاتف في الهواء بشكل رقم 8 (∞) للمعايرة"
                            },
                            color = TextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // 4. اللوحة السفلية للمعلومات الدقيقة (المسافة للكعبة، زاوية القبلة، حالة الـ GPS، دقة البوصلة)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isGpsEnabled || !hasLocationPermission) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!hasLocationPermission) {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                } else {
                                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg.copy(alpha = 0.95f)),
                        border = BorderStroke(1.dp, PrimaryGold)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(AppIcons.MyLocation, contentDescription = null, tint = PrimaryGold, modifier = Modifier.size(20.dp))
                                Text(
                                    text = if (!hasLocationPermission) "اضغط لمنح صلاحية الموقع لأعلى دقة"
                                    else "الـ GPS مغلق — اضغط لتفعيله لدقة أعلى",
                                    fontSize = 12.sp,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "تفعيل",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryGold
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg.copy(alpha = 0.92f)),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.35f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QiblaStatItem(
                                label = "زاوية القبلة",
                                value = if (hasValidLocation) "${qiblaBearing.roundToInt()}°" else "--°",
                                valueColor = PrimaryGold
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(36.dp)
                                    .background(ProgressTrack)
                            )
                            QiblaStatItem(
                                label = "اتجاهك الحالي",
                                value = if (hasAzimuthFix) "${smoothAzimuth.roundToInt()}°" else "--°",
                                valueColor = activeAccentColor
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(36.dp)
                                    .background(ProgressTrack)
                            )
                            QiblaStatItem(
                                label = "المسافة للكعبة",
                                value = if (hasValidLocation) "${String.format(Locale.US, "%,d", distanceKm.roundToInt())} كم" else "-- كم",
                                valueColor = TextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = ProgressTrack.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = locationSource,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            val accuracyLabel = when (sensorAccuracy) {
                                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "دقة البوصلة: ممتازة"
                                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "دقة البوصلة: متوسطة"
                                else -> "حرّك الهاتف بشكل (∞) للمعايرة"
                            }
                            Text(
                                text = accuracyLabel,
                                fontSize = 11.sp,
                                color = if (sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH) CompletedGreen else PrimaryGold,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) PrimaryGold else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (selected) ScreenBg else TextPrimary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) ScreenBg else TextPrimary
        )
    }
}

@Composable
private fun QiblaStatItem(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * شريط البوصلة الأفقية العلوي (مثل Google Qibla Finder)
 */
private fun DrawScope.drawHorizonRibbon(
    width: Float,
    azimuth: Float,
    qiblaBearing: Float,
    accentColor: Color,
    degreePaint: Paint
) {
    val barHeight = 38.dp.toPx()
    drawRect(
        color = HeaderBg.copy(alpha = 0.85f),
        topLeft = Offset(0f, 0f),
        size = Size(width, barHeight)
    )
    val fovDeg = 90f
    val pxPerDeg = width / fovDeg
    val centerX = width / 2f

    // رسم تدريجات الدرجات كل 15 درجة
    val startDeg = (azimuth - fovDeg / 2f).toInt() - 15
    val endDeg = (azimuth + fovDeg / 2f).toInt() + 15
    for (deg in startDeg..endDeg) {
        if (deg % 15 == 0) {
            val normDeg = (deg % 360 + 360) % 360
            val diff = ((normDeg - azimuth + 540f) % 360f) - 180f
            if (abs(diff) <= fovDeg / 2f) {
                val x = centerX + diff * pxPerDeg
                val isCardinal = normDeg % 90 == 0
                drawLine(
                    color = if (isCardinal) PrimaryGold else TextSecondary.copy(alpha = 0.5f),
                    start = Offset(x, barHeight - if (isCardinal) 14.dp.toPx() else 8.dp.toPx()),
                    end = Offset(x, barHeight),
                    strokeWidth = if (isCardinal) 2.dp.toPx() else 1.dp.toPx()
                )
                val label = when (normDeg) {
                    0 -> "ش"
                    90 -> "ق"
                    180 -> "ج"
                    270 -> "غ"
                    else -> null
                }
                if (label != null) {
                    degreePaint.textSize = 12.dp.toPx()
                    degreePaint.color = android.graphics.Color.parseColor("#C9A24D")
                    drawContext.canvas.nativeCanvas.drawText(label, x, 17.dp.toPx(), degreePaint)
                }
            }
        }
    }

    // علامة موقع الكعبة على شريط الأفق
    val qiblaDiff = ((qiblaBearing - azimuth + 540f) % 360f) - 180f
    val clampedX = (centerX + qiblaDiff * pxPerDeg).coerceIn(14.dp.toPx(), width - 14.dp.toPx())
    drawCircle(
        color = accentColor,
        radius = 6.dp.toPx(),
        center = Offset(clampedX, barHeight - 7.dp.toPx())
    )

    // مؤشر المنتصف الثابت
    drawLine(
        color = accentColor,
        start = Offset(centerX, 0f),
        end = Offset(centerX, barHeight),
        strokeWidth = 2.dp.toPx()
    )
}

/**
 * رسم وضع البوصلة الذكية الفاخرة (تجمع بين أسلوب Google Qibla Finder والهوية الزمردية الذهبية)
 */
private fun DrawScope.drawLuxuryQiblaCompass(
    centerX: Float,
    centerY: Float,
    azimuth: Float,
    qiblaBearing: Float,
    angleDelta: Float,
    pitch: Float,
    roll: Float,
    isAligned: Boolean,
    pulseFraction: Float,
    arrowPhase: Float,
    accentColor: Color,
    cardinalPaint: Paint,
    degreePaint: Paint
) {
    val outerRadius = min(size.width, size.height) * 0.34f
    val center = Offset(centerX, centerY)

    // 1. هالة التوهج عند المحاذاة الصحيحة للقبلة
    if (isAligned) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CompletedGreen.copy(alpha = 0.32f),
                    CompletedGreen.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = center,
                radius = outerRadius * 1.32f * pulseFraction
            ),
            radius = outerRadius * 1.32f * pulseFraction,
            center = center
        )
    }

    // 2. قوس التوجيه الديناميكي على المحيط الخارجي (مثل Google Qibla Finder)
    // يوضح المسار الأقصر للدوران من اتجاه الهاتف الحالي (الأعلى -90°) إلى اتجاه القبلة
    val arcRadius = outerRadius + 16.dp.toPx()
    drawCircle(
        color = ProgressTrack.copy(alpha = 0.55f),
        radius = arcRadius,
        center = center,
        style = Stroke(width = 4.dp.toPx())
    )
    if (!isAligned) {
        drawArc(
            color = PrimaryGold,
            startAngle = -90f,
            sweepAngle = angleDelta,
            useCenter = false,
            topLeft = Offset(centerX - arcRadius, centerY - arcRadius),
            size = Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(
                width = 5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), phase = -arrowPhase * 30f)
            )
        )
    } else {
        drawCircle(
            color = CompletedGreen,
            radius = arcRadius,
            center = center,
            style = Stroke(width = 5.dp.toPx())
        )
    }

    // 3. قرص البوصلة الدوار
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF194B34), CardBg, ScreenBg),
            center = center,
            radius = outerRadius
        ),
        radius = outerRadius,
        center = center
    )
    drawCircle(
        color = activeBorderColor(isAligned),
        radius = outerRadius,
        center = center,
        style = Stroke(width = 2.5.dp.toPx())
    )

    // تدوير تدريجات البوصلة والاتجاهات الأربعة عكس زاوية الهاتف
    rotate(degrees = -azimuth, pivot = center) {
        for (i in 0 until 72) {
            val angleDeg = i * 5f
            val angleRad = Math.toRadians((angleDeg - 90f).toDouble())
            val isMajor = i % 18 == 0
            val isMedium = i % 6 == 0
            val tickLen = when {
                isMajor -> 16.dp.toPx()
                isMedium -> 10.dp.toPx()
                else -> 5.dp.toPx()
            }
            val startR = outerRadius - 6.dp.toPx()
            val endR = startR - tickLen
            val sx = centerX + (startR * cos(angleRad)).toFloat()
            val sy = centerY + (startR * sin(angleRad)).toFloat()
            val ex = centerX + (endR * cos(angleRad)).toFloat()
            val ey = centerY + (endR * sin(angleRad)).toFloat()

            drawLine(
                color = when {
                    i == 0 -> CompletedGreen
                    isMajor -> PrimaryGold
                    isMedium -> TextPrimary.copy(alpha = 0.7f)
                    else -> TextSecondary.copy(alpha = 0.35f)
                },
                start = Offset(sx, sy),
                end = Offset(ex, ey),
                strokeWidth = if (isMajor) 2.5.dp.toPx() else 1.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // كتابة الاتجاهات الأصلية (ش، ق، ج، غ)
        val labels = listOf(0f to "ش", 90f to "ق", 180f to "ج", 270f to "غ")
        val textRadius = outerRadius - 34.dp.toPx()
        cardinalPaint.textSize = 15.dp.toPx()
        for ((deg, lbl) in labels) {
            val rad = Math.toRadians((deg - 90f).toDouble())
            val tx = centerX + (textRadius * cos(rad)).toFloat()
            val ty = centerY + (textRadius * sin(rad)).toFloat() + 5.dp.toPx()
            cardinalPaint.color = if (deg == 0f) {
                android.graphics.Color.parseColor("#57B96C")
            } else {
                android.graphics.Color.parseColor("#C9A24D")
            }
            drawContext.canvas.nativeCanvas.drawText(lbl, tx, ty, cardinalPaint)
        }
    }

    // 4. إبرة القبلة الذهبية وبوابة الكعبة المشرفة (تتحرك بزاوية angleDelta)
    rotate(degrees = angleDelta, pivot = center) {
        // شعاع التوجيه من المركز إلى الكعبة
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(accentColor, accentColor.copy(alpha = 0.1f)),
                startY = centerY - outerRadius * 0.82f,
                endY = centerY
            ),
            start = Offset(centerX, centerY),
            end = Offset(centerX, centerY - outerRadius * 0.78f),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )

        // إبرة البوصلة المزدوجة الفاخرة
        val needleTop = centerY - outerRadius * 0.56f
        val needleBottom = centerY + outerRadius * 0.35f
        val needleHalfW = 10.dp.toPx()

        val northNeedle = Path().apply {
            moveTo(centerX, needleTop)
            lineTo(centerX + needleHalfW, centerY)
            lineTo(centerX - needleHalfW, centerY)
            close()
        }
        drawPath(northNeedle, color = accentColor)

        val southNeedle = Path().apply {
            moveTo(centerX, needleBottom)
            lineTo(centerX + needleHalfW * 0.75f, centerY)
            lineTo(centerX - needleHalfW * 0.75f, centerY)
            close()
        }
        drawPath(southNeedle, color = ProgressTrack)

        // أيقونة الكعبة المشرفة على طرف شعاع القبلة
        val kaabaCenter = Offset(centerX, centerY - outerRadius - 16.dp.toPx())
        drawKaabaIcon(
            center = kaabaCenter,
            boxSize = 32.dp.toPx(),
            accentColor = accentColor,
            isAligned = isAligned,
            pulseFraction = pulseFraction
        )
    }

    // 5. عدسة المحاذاة العلوية الثابتة (عند الساعة 12)
    val topTarget = Offset(centerX, centerY - outerRadius - 16.dp.toPx())
    drawCircle(
        color = accentColor,
        radius = 22.dp.toPx(),
        center = topTarget,
        style = Stroke(width = 2.dp.toPx())
    )

    // 6. ميزان استواء الهاتف ثلاثي الأبعاد في قلب البوصلة (Bubble Level)
    val bubbleMaxOffset = outerRadius * 0.22f
    val bubbleX = (centerX + (roll / 45f).coerceIn(-1f, 1f) * bubbleMaxOffset)
    val bubbleY = (centerY + (pitch / 45f).coerceIn(-1f, 1f) * bubbleMaxOffset)

    drawCircle(
        color = ScreenBg.copy(alpha = 0.85f),
        radius = bubbleMaxOffset + 8.dp.toPx(),
        center = center
    )
    drawCircle(
        color = PrimaryGold.copy(alpha = 0.35f),
        radius = bubbleMaxOffset + 8.dp.toPx(),
        center = center,
        style = Stroke(width = 1.5.dp.toPx())
    )
    drawCircle(
        color = accentColor.copy(alpha = 0.85f),
        radius = 7.dp.toPx(),
        center = Offset(bubbleX, bubbleY)
    )
    drawCircle(
        color = PrimaryGold,
        radius = 3.dp.toPx(),
        center = center
    )
}

/**
 * رسم واجهة الواقع المعزز (AR Qibla Finder) فوق الكاميرا المباشرة
 */
private fun DrawScope.drawArQiblaFinderOverlay(
    centerX: Float,
    centerY: Float,
    angleDelta: Float,
    elevationDeg: Float,
    horizontalFov: Float,
    isAligned: Boolean,
    pulseFraction: Float,
    arrowPhase: Float,
    accentColor: Color,
    distanceKm: Double
) {
    val center = Offset(centerX, centerY)
    val reticleRadius = 56.dp.toPx()

    // إسقاط دقيق لبوابة الكعبة باستخدام مجال الرؤية البصري الحقيقي لعدسة الكاميرا (FOV) وزاوية الأفق الحقيقية (0° = الأفق)
    val safeFov = horizontalFov.coerceIn(40f, 80f)
    val pxPerDegX = size.width / safeFov
    val rawKaabaX = centerX + angleDelta * pxPerDegX
    val rawKaabaY = (centerY + (elevationDeg.coerceIn(-35f, 35f) * (size.height / (safeFov * 1.45f))))
        .coerceIn(130.dp.toPx(), size.height * 0.68f)

    val clampedKaabaX = rawKaabaX.coerceIn(48.dp.toPx(), size.width - 48.dp.toPx())
    val kaabaPos = Offset(clampedKaabaX, rawKaabaY)

    // 1. خط التوجيه المنقط والأسهم المتحركة من عدسة المنتصف إلى الكعبة (مثل Google Qibla Finder)
    if (!isAligned) {
        drawLine(
            color = PrimaryGold,
            start = center,
            end = kaabaPos,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f), phase = -arrowPhase * 36f)
        )
    }

    // 2. عدسة التركيز الوسطى (Qibla Finder Target Ring)
    drawCircle(
        color = accentColor.copy(alpha = if (isAligned) 0.25f else 0.08f),
        radius = reticleRadius * (if (isAligned) pulseFraction else 1f),
        center = center
    )
    drawCircle(
        color = accentColor,
        radius = reticleRadius,
        center = center,
        style = Stroke(width = if (isAligned) 3.5.dp.toPx() else 2.dp.toPx())
    )

    // 3. بوابة الكعبة المشرفة العائمة بالواقع المعزز
    drawKaabaIcon(
        center = kaabaPos,
        boxSize = 48.dp.toPx(),
        accentColor = accentColor,
        isAligned = isAligned,
        pulseFraction = pulseFraction
    )
}

/**
 * الرادار السفلي المنظوري (مثل الدائرة السفلية في Google Qibla Finder)
 */
private fun DrawScope.drawBottomPerspectiveRadar(
    centerX: Float,
    centerY: Float,
    radius: Float,
    azimuth: Float,
    qiblaBearing: Float,
    accentColor: Color
) {
    val center = Offset(centerX, centerY)
    drawCircle(
        color = ScreenBg.copy(alpha = 0.78f),
        radius = radius,
        center = center
    )
    drawCircle(
        color = PrimaryGold.copy(alpha = 0.6f),
        radius = radius,
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )

    // مخروط الرؤية الأمامي للكاميرا
    val fovPath = Path().apply {
        moveTo(centerX, centerY)
        lineTo(centerX - radius * 0.55f, centerY - radius * 0.82f)
        lineTo(centerX + radius * 0.55f, centerY - radius * 0.82f)
        close()
    }
    drawPath(fovPath, color = accentColor.copy(alpha = 0.22f))

    // نقطة موقع الكعبة على محيط الرادار
    val relAngleRad = Math.toRadians((qiblaBearing - azimuth - 90f).toDouble())
    val kx = centerX + (radius * 0.82f * cos(relAngleRad)).toFloat()
    val ky = centerY + (radius * 0.82f * sin(relAngleRad)).toFloat()
    drawLine(
        color = accentColor,
        start = center,
        end = Offset(kx, ky),
        strokeWidth = 2.5.dp.toPx(),
        cap = StrokeCap.Round
    )
    drawCircle(
        color = accentColor,
        radius = 5.dp.toPx(),
        center = Offset(kx, ky)
    )
    drawCircle(
        color = TextPrimary,
        radius = 3.5.dp.toPx(),
        center = center
    )
}

/**
 * رسم مجسم الكعبة المشرفة بالحزام الذهبي وهالة النور
 */
private fun DrawScope.drawKaabaIcon(
    center: Offset,
    boxSize: Float,
    accentColor: Color,
    isAligned: Boolean,
    pulseFraction: Float
) {
    // هالة دائرية خلف الكعبة
    drawCircle(
        color = accentColor.copy(alpha = if (isAligned) 0.38f else 0.2f),
        radius = (boxSize * 0.78f) * (if (isAligned) pulseFraction else 1f),
        center = center
    )
    drawCircle(
        color = ScreenBg,
        radius = boxSize * 0.65f,
        center = center
    )
    drawCircle(
        color = accentColor,
        radius = boxSize * 0.65f,
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )

    // مكعب الكعبة الأسود الفاخر
    val cubeW = boxSize * 0.62f
    val cubeH = boxSize * 0.68f
    val topLeft = Offset(center.x - cubeW / 2f, center.y - cubeH / 2f)
    drawRoundRect(
        color = Color(0xFF101413),
        topLeft = topLeft,
        size = Size(cubeW, cubeH),
        cornerRadius = CornerRadius(3.dp.toPx())
    )
    drawRoundRect(
        color = PrimaryGold.copy(alpha = 0.8f),
        topLeft = topLeft,
        size = Size(cubeW, cubeH),
        cornerRadius = CornerRadius(3.dp.toPx()),
        style = Stroke(width = 1.2.dp.toPx())
    )

    // الحزام الذهبي لكسوة الكعبة المشرفة
    val bandY = topLeft.y + cubeH * 0.26f
    drawLine(
        color = PrimaryGold,
        start = Offset(topLeft.x + 2.dp.toPx(), bandY),
        end = Offset(topLeft.x + cubeW - 2.dp.toPx(), bandY),
        strokeWidth = 3.dp.toPx()
    )

    // باب الكعبة الذهبي
    val doorW = cubeW * 0.24f
    val doorH = cubeH * 0.34f
    drawRoundRect(
        color = PrimaryGold,
        topLeft = Offset(center.x + cubeW * 0.05f, topLeft.y + cubeH * 0.48f),
        size = Size(doorW, doorH),
        cornerRadius = CornerRadius(1.5.dp.toPx())
    )
}

private fun activeBorderColor(isAligned: Boolean): Color =
    if (isAligned) CompletedGreen else PrimaryGold.copy(alpha = 0.55f)

/**
 * معاينة الكاميرا الخلفية المباشرة باستخدام Camera2 API مع ضبط نسبة الأبعاد (Center-Crop Matrix بدون مطّ)،
 * وقياس مجال الرؤية البصري الحقيقي للعدسة (FOV)، وإغلاق محكم مع دورة الحياة لمنع تسرب الموارد.
 */
@SuppressLint("MissingPermission")
@Composable
private fun CameraPreviewSurface(
    modifier: Modifier = Modifier,
    onHorizontalFovMeasured: (Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraHolder = remember { arrayOfNulls<CameraDevice>(1) }
    val sessionHolder = remember { arrayOfNulls<CameraCaptureSession>(1) }
    val textureViewHolder = remember { arrayOfNulls<TextureView>(1) }
    val previewSizeHolder = remember { intArrayOf(1280, 720) }
    val isOpeningHolder = remember { booleanArrayOf(false) }

    fun closeCameraSafely() {
        isOpeningHolder[0] = false
        try {
            sessionHolder[0]?.close()
        } catch (_: Exception) {}
        sessionHolder[0] = null
        try {
            cameraHolder[0]?.close()
        } catch (_: Exception) {}
        cameraHolder[0] = null
    }

    fun startCameraIfReady(tv: TextureView) {
        if (cameraHolder[0] != null || isOpeningHolder[0]) return
        val surface = tv.surfaceTexture ?: return
        if (!tv.isAvailable || tv.width <= 0 || tv.height <= 0) return
        isOpeningHolder[0] = true
        openRearCamera(
            context = context,
            textureView = tv,
            surfaceTexture = surface,
            viewWidth = tv.width,
            viewHeight = tv.height,
            onPreviewSizeChosen = { pw, ph ->
                previewSizeHolder[0] = pw
                previewSizeHolder[1] = ph
            },
            onFovMeasured = onHorizontalFovMeasured
        ) { cam, session ->
            isOpeningHolder[0] = false
            cameraHolder[0] = cam
            sessionHolder[0] = session
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    textureViewHolder[0]?.let { startCameraIfReady(it) }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    closeCameraSafely()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            closeCameraSafely()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                textureViewHolder[0] = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        startCameraIfReady(this@apply)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        applyCenterCropTransform(
                            textureView = this@apply,
                            previewW = previewSizeHolder[0],
                            previewH = previewSizeHolder[1],
                            viewWidth = width,
                            viewHeight = height
                        )
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        closeCameraSafely()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    )
}

private fun applyCenterCropTransform(
    textureView: TextureView,
    previewW: Int,
    previewH: Int,
    viewWidth: Int,
    viewHeight: Int
): Float {
    if (viewWidth <= 0 || viewHeight <= 0 || previewW <= 0 || previewH <= 0) return 1f
    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f
    val bufferAspect = previewH.toFloat() / previewW.toFloat()
    val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
    val scaleX = if (bufferAspect > viewAspect) bufferAspect / viewAspect else 1f
    val scaleY = if (bufferAspect < viewAspect) viewAspect / bufferAspect else 1f
    matrix.setScale(scaleX, scaleY, centerX, centerY)
    textureView.setTransform(matrix)
    return scaleX
}

@SuppressLint("MissingPermission")
private fun openRearCamera(
    context: Context,
    textureView: TextureView,
    surfaceTexture: SurfaceTexture,
    viewWidth: Int,
    viewHeight: Int,
    onPreviewSizeChosen: (Int, Int) -> Unit,
    onFovMeasured: (Float) -> Unit,
    onOpened: (CameraDevice?, CameraCaptureSession?) -> Unit
) {
    try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            onOpened(null, null)
            return
        }

        val chars = manager.getCameraCharacteristics(cameraId)

        // اختيار أنسب دقة معاينة مدعومة وتطبيق مصفوفة Center-Crop لمنع أي مطّ للصورة
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val availableSizes = streamMap?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val chosenSize = availableSizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width * it.height }
            ?: availableSizes.firstOrNull()

        val previewW = chosenSize?.width ?: 1280
        val previewH = chosenSize?.height ?: 720
        onPreviewSizeChosen(previewW, previewH)
        surfaceTexture.setDefaultBufferSize(previewW, previewH)

        val cropScaleX = applyCenterCropTransform(
            textureView = textureView,
            previewW = previewW,
            previewH = previewH,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )

        // حساب مجال الرؤية البصري الحقيقي للعدسة بعد Center-Crop حتى تتطابق الكعبة مع الأفق بدقة
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focalLengths?.firstOrNull()
        if (sensorSize != null && focalLength != null && focalLength > 0f) {
            val portraitSensorWidth = min(sensorSize.width, sensorSize.height).toDouble()
            val rawHalfTan = portraitSensorWidth / (2.0 * focalLength.toDouble())
            val effectiveFovRad = 2.0 * atan(rawHalfTan / cropScaleX.coerceAtLeast(1f).toDouble())
            val fovDeg = Math.toDegrees(effectiveFovRad).toFloat().coerceIn(38f, 75f)
            onFovMeasured(fovDeg)
        }

        val previewSurface = Surface(surfaceTexture)
        val mainHandler = Handler(Looper.getMainLooper())

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                try {
                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(
                        listOf(previewSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.setRepeatingRequest(requestBuilder.build(), null, mainHandler)
                                    onOpened(camera, session)
                                } catch (_: Exception) {
                                    onOpened(camera, null)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                onOpened(camera, null)
                            }
                        },
                        mainHandler
                    )
                } catch (_: Exception) {
                    camera.close()
                    onOpened(null, null)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                onOpened(null, null)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                onOpened(null, null)
            }
        }, mainHandler)
    } catch (_: Exception) {
        onOpened(null, null)
    }
}

private fun lowPassFilter(input: FloatArray, output: FloatArray, alpha: Float) {
    for (i in input.indices) {
        output[i] = output[i] + alpha * (input[i] - output[i])
    }
}
