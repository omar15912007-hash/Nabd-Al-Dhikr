package com.nabdaldhikr.app.updater

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.nabdaldhikr.app.BuildConfig
import com.nabdaldhikr.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    @SerializedName("versionCode")
    val versionCode: Int = 1,

    @SerializedName("versionName")
    val versionName: String = "1.0.0",

    @SerializedName("apkUrl")
    val apkUrl: String = "",

    @SerializedName("changelog")
    val changelog: String = "",

    @SerializedName("forceUpdate")
    val forceUpdate: Boolean = false
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    object UpToDate : UpdateState()
    data class Downloading(val progress: Float, val downloadedMb: Float, val totalMb: Float) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * Reliable In-App Auto-Update Manager powered by OkHttp:
 *  - Checks `version.json` with GitHub Releases API fallback.
 *  - Resumable chunked download via `.part` staging + ZIP (`PK`) header integrity validation.
 *  - Detects one-time signing certificate transitions (legacy debug key -> permanent release key)
 *    before calling PackageInstaller so the user sees clear guidance + a direct browser fallback.
 */
object AppUpdateManager {

    private const val GITHUB_OWNER = "omar15912007-hash"
    private const val GITHUB_REPO = "Nabd-Al-Dhikr"
    private const val PROGRESS_EMIT_INTERVAL_MS = 150L
    private const val MIN_VALID_APK_BYTES = 1_000_000L

    const val RAW_VERSION_URL =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/version.json"
    const val GITHUB_RELEASES_API =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun checkForUpdate(
        rawUrl: String = RAW_VERSION_URL,
        fallbackApiUrl: String = GITHUB_RELEASES_API
    ): UpdateState = withContext(Dispatchers.IO) {
        // Attempt 1: Fast raw version.json on main branch
        try {
            val req = Request.Builder()
                .url(rawUrl)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "NabdAlDhikr-App/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = resp.body?.string().orEmpty()
                    val info = Gson().fromJson(json, UpdateInfo::class.java)
                    if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                        val resolvedUrl = if (info.apkUrl.isNotBlank() && !info.apkUrl.contains("/-/")) {
                            info.apkUrl
                        } else {
                            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v${info.versionName}/NabdAlDhikr.apk"
                        }
                        return@withContext UpdateState.UpdateAvailable(info.copy(apkUrl = resolvedUrl))
                    } else if (info != null) {
                        return@withContext UpdateState.UpToDate
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to GitHub Releases REST API
        }

        // Attempt 2: Official GitHub Releases API
        try {
            val apiReq = Request.Builder()
                .url(fallbackApiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "NabdAlDhikr-App/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(apiReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateState.Error("فشل الاتصال بخادم التحديث (رمز: ${resp.code})")
                }
                val jsonText = resp.body?.string().orEmpty()
                val root = JsonParser.parseString(jsonText).asJsonObject
                val tagName = root.get("tag_name")?.asString ?: "v1.0.0"
                val body = root.get("body")?.asString ?: "تحسينات عامة وإصلاحات."

                var apkDownloadUrl = ""
                if (root.has("assets") && root.get("assets").isJsonArray) {
                    for (element in root.getAsJsonArray("assets")) {
                        val assetObj = element.asJsonObject
                        val name = assetObj.get("name")?.asString ?: ""
                        if (name.equals("NabdAlDhikr.apk", ignoreCase = true) || name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = assetObj.get("browser_download_url")?.asString ?: ""
                            break
                        }
                    }
                }
                if (apkDownloadUrl.isEmpty()) {
                    apkDownloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/NabdAlDhikr.apk"
                }

                val remoteVersionName = tagName.removePrefix("v").trim()
                val remoteVersionCode = parseVersionCode(remoteVersionName)
                if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                    return@withContext UpdateState.UpdateAvailable(
                        UpdateInfo(
                            versionCode = remoteVersionCode,
                            versionName = remoteVersionName,
                            apkUrl = apkDownloadUrl,
                            changelog = body,
                            forceUpdate = false
                        )
                    )
                }
                return@withContext UpdateState.UpToDate
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return@withContext UpdateState.Error(e.localizedMessage ?: "حدث خطأ أثناء فحص التحديثات")
        }
    }

    private fun parseVersionCode(versionName: String): Int {
        return try {
            val parts = versionName.split(".").map { it.filter { ch -> ch.isDigit() } }
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            major * 10000 + minor * 100 + patch
        } catch (_: Exception) {
            1
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        versionName: String = "",
        onProgress: (progress: Float, downloadedMb: Float, totalMb: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updateDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!updateDir.exists()) updateDir.mkdirs()

        val targetFile = File(updateDir, "nabd_aldhikr_update.apk")
        val partFile = File(updateDir, "nabd_aldhikr_update.apk.part")

        try {
            var existingBytes = if (partFile.isFile) partFile.length() else 0L
            val requestBuilder = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NabdAlDhikr-Android-Updater")
            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 416) {
                    // Range invalid (stale .part file); restart clean once
                    partFile.delete()
                    existingBytes = 0L
                } else if (!response.isSuccessful && response.code != 206) {
                    return@withContext Result.failure(
                        IOException("فشل التحميل من الخادم (HTTP ${response.code})")
                    )
                }

                val targetResponse = if (response.code == 416) {
                    httpClient.newCall(
                        Request.Builder().url(downloadUrl).header("User-Agent", "NabdAlDhikr-Android-Updater").build()
                    ).execute()
                } else {
                    null
                }

                val activeResponse = targetResponse ?: response
                try {
                    if (!activeResponse.isSuccessful && activeResponse.code != 206) {
                        return@withContext Result.failure(
                            IOException("فشل التحميل من الخادم (HTTP ${activeResponse.code})")
                        )
                    }

                    val appendMode = activeResponse.code == 206 && existingBytes > 0L
                    val startOffset = if (appendMode) existingBytes else 0L
                    val body = activeResponse.body ?: return@withContext Result.failure(
                        IOException("استجابة فارغة من خادم التحديث.")
                    )
                    val contentLength = body.contentLength()
                    val totalBytes = if (contentLength > 0L) startOffset + contentLength else -1L

                    body.byteStream().use { input ->
                        FileOutputStream(partFile, appendMode).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var totalRead = startOffset
                            var lastEmitMs = 0L

                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                    lastEmitMs = now
                                    val progress = if (totalBytes > 0) (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                                    val downloadedMb = totalRead / (1024f * 1024f)
                                    val totalMb = if (totalBytes > 0) (totalBytes / (1024f * 1024f)) else 12.5f
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress, downloadedMb, totalMb)
                                    }
                                }
                            }
                            output.flush()
                            val downloadedMb = totalRead / (1024f * 1024f)
                            val totalMb = if (totalBytes > 0) (totalBytes / (1024f * 1024f)) else downloadedMb
                            withContext(Dispatchers.Main) {
                                onProgress(1f, downloadedMb, totalMb)
                            }
                        }
                    }
                } finally {
                    targetResponse?.close()
                }
            }

            if (!isValidApkFile(partFile)) {
                partFile.delete()
                return@withContext Result.failure(
                    IOException("الملف المحمّل تالف أو غير مكتمل — يمكنك المحاولة مجدداً أو التحميل عبر المتصفح.")
                )
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            val publicName = if (versionName.isNotBlank()) "NabdAlDhikr-v$versionName.apk" else "NabdAlDhikr.apk"
            saveCopyToPublicDownloads(context, targetFile, publicName)

            Result.success(targetFile)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(IOException(e.localizedMessage ?: "تعذر تنزيل التحديث؛ تأكد من اتصال الإنترنت."))
        }
    }

    fun isValidApkFile(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_VALID_APK_BYTES) return false
        return try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    magic[0] == 0x50.toByte() &&
                    magic[1] == 0x4B.toByte() &&
                    magic[2] == 0x03.toByte() &&
                    magic[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Compares the SHA-256 signing certificate of the downloaded APK against the currently
     * installed app. Returns false when upgrading from legacy ephemeral debug builds (<= v1.0.5)
     * to the permanent production release keystore (>= v1.0.6).
     */
    fun doSignaturesMatchInstalledApp(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val installedHashes = readInstalledCertHashes(pm, context.packageName)
            val archiveHashes = readArchiveCertHashes(pm, apkFile)
            if (installedHashes.isEmpty() || archiveHashes.isEmpty()) return true
            installedHashes.any { it in archiveHashes }
        } catch (_: Exception) {
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun readInstalledCertHashes(pm: PackageManager, pkgName: String): Set<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners.orEmpty()
            signers.map { sha256Hex(it.toByteArray()) }.toSet()
        } else {
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            info.signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        }
    }

    @Suppress("DEPRECATION")
    private fun readArchiveCertHashes(pm: PackageManager, apkFile: File): Set<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info?.signingInfo?.apkContentsSigners.orEmpty()
            signers.map { sha256Hex(it.toByteArray()) }.toSet()
        } else {
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            info?.signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(manageIntent)
        }
    }

    fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun triggerInstall(context: Context, apkFile: File): Boolean {
        if (!canInstallPackages(context)) {
            openInstallPermissionSettings(context)
            return false
        }

        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Saves an accessible copy of the verified APK into public `Downloads` (Android 10+ scoped storage)
     * so if an older build with an incompatible signing certificate must be uninstalled once first,
     * the user already has the installer ready locally in Downloads.
     */
    fun saveCopyToPublicDownloads(context: Context, sourceApk: File, displayName: String): Boolean {
        if (!sourceApk.isFile) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(displayName)
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(itemUri)?.use { output ->
                FileInputStream(sourceApk).use { input ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            } ?: run {
                resolver.delete(itemUri, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

@Composable
fun AppUpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedMb by remember { mutableFloatStateOf(0f) }
    var totalMb by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var hasSignatureConflict by remember { mutableStateOf(false) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var hasInstallPermission by remember { mutableStateOf(AppUpdateManager.canInstallPackages(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = AppUpdateManager.canInstallPackages(context)
                hasInstallPermission = granted
                val file = downloadedFile
                if (granted && awaitingPermission && file != null && file.exists() && !hasSignatureConflict) {
                    awaitingPermission = false
                    if (!AppUpdateManager.triggerInstall(context, file)) {
                        errorMessage = "تعذر فتح المثبت مباشرة؛ استخدم خيار التحميل عبر المتصفح بالأسفل."
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Dialog(
        onDismissRequest = { if (!isDownloading && !updateInfo.forceUpdate) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading && !updateInfo.forceUpdate,
            dismissOnClickOutside = !isDownloading && !updateInfo.forceUpdate
        )
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(HeaderBg)
                        .border(1.dp, PrimaryGold, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✨", fontSize = 22.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "تحديث جديد متوفر",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryGold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = HeaderBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "الإصدار v${updateInfo.versionName}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (updateInfo.changelog.isNotBlank()) {
                    Text(
                        text = "ما الجديد في هذا التحديث:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ScreenBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 130.dp)
                    ) {
                        Text(
                            text = updateInfo.changelog,
                            fontSize = 12.5.sp,
                            color = TextPrimary.copy(alpha = 0.92f),
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                            lineHeight = 19.sp
                        )
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = Color(0xFFE57373),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = PrimaryGold,
                        trackColor = ProgressTrack
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryGold
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f / %.1f MB", downloadedMb, totalMb),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                if (downloadedFile != null && hasSignatureConflict) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x33D4AF37),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryGold),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "ترقية التوقيع الرسمي الدائم (لمرة واحدة)",
                                fontWeight = FontWeight.Bold,
                                color = PrimaryGold,
                                fontSize = 12.5.sp
                            )
                            Text(
                                text = "نسختك الحالية قديمة بتوقيع تجريبي مختلف، لذلك يرفض أندرويد استبدالها مباشرة. تم حفظ التحديث الجديد في مجلد التنزيلات (Downloads) باسم NabdAlDhikr-v${updateInfo.versionName}.apk — احذف التطبيق القديم مرة واحدة فقط ثم ثبّت النسخة الجديدة (ومع حماية جوجل اختر «مزيد من التفاصيل ← التثبيت على أي حال»). كل التحديثات القادمة ستُثبّت من داخل التطبيق دون حذف.",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                } else if (downloadedFile != null && !hasInstallPermission) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HeaderBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🔐 مطلوب إذن تثبيت التحديث",
                                fontWeight = FontWeight.Bold,
                                color = PrimaryGold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "فعّل «السماح بتثبيت التطبيقات من هذا المصدر»، ولو ظهرت حماية جوجل اضغط «مزيد من التفاصيل ← التثبيت على أي حال».",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                        }
                    }
                } else if (downloadedFile != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "ملاحظة حماية جوجل (Play Protect): عند ظهور نافذة الفحص اضغط «مزيد من التفاصيل ← التثبيت على أي حال».",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!updateInfo.forceUpdate && !isDownloading) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Text(text = "لاحقاً", fontSize = 14.sp)
                        }
                    }

                    val buttonText = when {
                        isDownloading -> "جاري التحميل..."
                        downloadedFile != null && !hasInstallPermission -> "منح إذن التثبيت"
                        downloadedFile != null -> "تثبيت الآن"
                        else -> "تحديث الآن"
                    }

                    Button(
                        onClick = {
                            val currentFile = downloadedFile
                            if (currentFile != null && currentFile.exists()) {
                                if (hasInstallPermission) {
                                    if (!AppUpdateManager.triggerInstall(context, currentFile)) {
                                        errorMessage = "تعذر تشغيل المثبت؛ استخدم رابط التحميل المباشر بالأسفل."
                                    }
                                } else {
                                    awaitingPermission = true
                                    AppUpdateManager.openInstallPermissionSettings(context)
                                }
                            } else {
                                isDownloading = true
                                errorMessage = null
                                coroutineScope.launch {
                                    val result = AppUpdateManager.downloadApk(
                                        context = context,
                                        downloadUrl = updateInfo.apkUrl,
                                        versionName = updateInfo.versionName
                                    ) { progress, downloaded, total ->
                                        downloadProgress = progress
                                        downloadedMb = downloaded
                                        totalMb = total
                                    }

                                    isDownloading = false
                                    result.onSuccess { file ->
                                        downloadedFile = file
                                        hasSignatureConflict = !AppUpdateManager.doSignaturesMatchInstalledApp(context, file)
                                        if (!hasSignatureConflict) {
                                            if (AppUpdateManager.canInstallPackages(context)) {
                                                if (!AppUpdateManager.triggerInstall(context, file)) {
                                                    errorMessage = "تعذر فتح المثبت؛ استخدم التحميل من المتصفح."
                                                }
                                            } else {
                                                awaitingPermission = true
                                                AppUpdateManager.openInstallPermissionSettings(context)
                                            }
                                        }
                                    }.onFailure { err ->
                                        errorMessage = err.localizedMessage ?: "فشل التحميل"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryGold,
                            contentColor = Color.Black
                        ),
                        enabled = !isDownloading
                    ) {
                        Text(text = buttonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (!isDownloading && (errorMessage != null || downloadedFile != null || hasSignatureConflict)) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = { AppUpdateManager.openInBrowser(context, updateInfo.apkUrl) }
                    ) {
                        Text(
                            text = "تحميل مباشر عبر المتصفح (رابط بديل)",
                            color = PrimaryGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
