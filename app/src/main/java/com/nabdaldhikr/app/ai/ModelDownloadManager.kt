package com.nabdaldhikr.app.ai

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Optional background downloader for Nabd Al-Dhikr offline AI assets hosted on GitHub Releases.
 * Features:
 *  - OkHttp + Kotlin Coroutines streaming I/O
 *  - HTTP Range resume support via `.part` staging files
 *  - Real-time download progress percentage & MB counters
 *  - Strict SHA-256 checksum verification before atomic activation
 */
class ModelDownloadManager(
    private val context: Context,
    private val gson: Gson = Gson(),
    private val client: OkHttpClient = defaultOkHttpClient()
) {
    companion object {
        private const val GITHUB_OWNER = "omar15912007-hash"
        private const val GITHUB_REPO = "Nabd-Al-Dhikr"
        private const val DEFAULT_AI_TAG = "ai-v1.0.0"

        const val DEFAULT_MANIFEST_URL =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$DEFAULT_AI_TAG/manifest.json"
        const val MODEL_FILENAME = "nabd_ai_minilm.onnx"
        const val TOKENIZER_FILENAME = "tokenizer.json"

        private const val BUFFER_BYTES = 16 * 1024
        private const val PROGRESS_EMIT_INTERVAL_MS = 120L

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val aiRootDir: File
        get() = File(context.filesDir, "nabd_ai").apply { if (!exists()) mkdirs() }

    private val _state = MutableStateFlow<AiDownloadState>(AiDownloadState.Idle)
    val state: StateFlow<AiDownloadState> = _state.asStateFlow()

    fun modelFile(): File = File(aiRootDir, MODEL_FILENAME)
    fun tokenizerFile(): File = File(aiRootDir, TOKENIZER_FILENAME)
    fun vectorsFile(langCode: String): File = File(aiRootDir, "vectors_${sanitizeCode(langCode)}.bin")
    fun datasetFile(langCode: String): File = File(aiRootDir, "db_${sanitizeCode(langCode)}.json")

    fun isBaseModelInstalled(): Boolean {
        return modelFile().isFile && modelFile().length() > 0L &&
            tokenizerFile().isFile && tokenizerFile().length() > 0L
    }

    fun isLanguagePackInstalled(langCode: String): Boolean {
        return isBaseModelInstalled() &&
            vectorsFile(langCode).isFile && vectorsFile(langCode).length() > 0L &&
            datasetFile(langCode).isFile && datasetFile(langCode).length() > 0L
    }

    fun getInstalledLanguages(): Set<String> {
        val dir = aiRootDir
        val files = dir.listFiles().orEmpty()
        return files
            .mapNotNull { file ->
                val match = Regex("^vectors_([a-zA-Z0-9_-]+)\\.bin$").matchEntire(file.name)
                val code = match?.groupValues?.getOrNull(1)
                if (code != null && datasetFile(code).isFile) code else null
            }
            .toSet()
    }

    fun getTotalInstalledSizeMb(): Float {
        val bytes = aiRootDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sumOf { it.length() }
        return bytes / (1024f * 1024f)
    }

    suspend fun fetchManifest(manifestUrl: String = DEFAULT_MANIFEST_URL): Result<AiManifest> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(manifestUrl)
                    .header("User-Agent", "NabdAlDhikr-Android")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("تعذر الاتصال بخادم حزم الذكاء الاصطناعي (HTTP ${response.code})")
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withContext Result.failure(IOException("ملف manifest.json فارغ"))
                    }
                    val manifest = gson.fromJson(body, AiManifest::class.java)
                    Result.success(manifest)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Downloads base ONNX model + tokenizer (if missing) and the selected language pack
     * with resume support and SHA-256 verification.
     */
    suspend fun downloadModelAndLanguagePack(
        languageCode: String,
        manifestUrl: String = DEFAULT_MANIFEST_URL
    ): Result<File> = withContext(Dispatchers.IO) {
        // Strict hardware compatibility hard-lock (impossible to bypass even programmatically)
        val capability = DeviceCapabilityChecker.inspect(context)
        if (!capability.canDownload) {
            val reasons = capability.failedReasonsAr.joinToString(" • ")
            val blockedMsg = "تم منع التحميل نهائياً لعدم توافق الجهاز مع متطلبات الذكاء الاصطناعي: $reasons"
            _state.value = AiDownloadState.Error(blockedMsg, canResume = false)
            return@withContext Result.failure(UnsupportedOperationException(blockedMsg))
        }

        _state.value = AiDownloadState.FetchingManifest
        val manifestResult = fetchManifest(manifestUrl)
        val manifest = manifestResult.getOrElse { err ->
            val msg = "تعذر جلب فهرس حزم الذكاء الاصطناعي من GitHub Releases: ${err.localizedMessage ?: "تحقق من اتصال الإنترنت"}"
            _state.value = AiDownloadState.Error(msg, canResume = true)
            return@withContext Result.failure(err)
        }

        val safeCode = sanitizeCode(languageCode)
        val langPack = manifest.languages.firstOrNull { it.code.equals(safeCode, ignoreCase = true) }
        if (langPack == null) {
            val msg = "حزمة اللغة ($safeCode) غير متوفرة حالياً في الإصدار ${manifest.releaseTag}."
            _state.value = AiDownloadState.Error(msg, canResume = false)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        try {
            if (!isFileValid(modelFile(), manifest.model.sha256)) {
                downloadResumableAsset(
                    asset = manifest.model,
                    destination = modelFile(),
                    stageLabel = "جاري تنزيل نموذج الذكاء الاصطناعي المضغوط (INT8)..."
                )
            }

            if (!isFileValid(tokenizerFile(), manifest.tokenizer.sha256)) {
                downloadResumableAsset(
                    asset = manifest.tokenizer,
                    destination = tokenizerFile(),
                    stageLabel = "جاري تنزيل المحلل اللغوي متعدد اللغات..."
                )
            }

            if (!isFileValid(vectorsFile(safeCode), langPack.vectors.sha256)) {
                downloadResumableAsset(
                    asset = langPack.vectors,
                    destination = vectorsFile(safeCode),
                    stageLabel = "جاري تنزيل متجهات البحث الدلالي (${langPack.nativeName})..."
                )
            }

            if (!isFileValid(datasetFile(safeCode), langPack.dataset.sha256)) {
                downloadResumableAsset(
                    asset = langPack.dataset,
                    destination = datasetFile(safeCode),
                    stageLabel = "جاري تنزيل قاعدة الأذكار والشروحات (${langPack.nativeName})..."
                )
            }

            _state.value = AiDownloadState.Ready(
                languageCode = safeCode,
                installedLanguages = getInstalledLanguages()
            )
            Result.success(modelFile())
        } catch (ce: CancellationException) {
            _state.value = AiDownloadState.Idle
            throw ce
        } catch (e: Exception) {
            val message = e.localizedMessage ?: "انقطع التنزيل؛ يمكنك الاستكمال من نفس النقطة."
            _state.value = AiDownloadState.Error(message = message, canResume = true)
            Result.failure(e)
        }
    }

    fun deleteLanguagePack(langCode: String) {
        val safeCode = sanitizeCode(langCode)
        vectorsFile(safeCode).delete()
        datasetFile(safeCode).delete()
        File(aiRootDir, "vectors_$safeCode.bin.part").delete()
        File(aiRootDir, "db_$safeCode.json.part").delete()
        _state.value = if (isBaseModelInstalled()) {
            AiDownloadState.Ready(
                languageCode = getInstalledLanguages().firstOrNull().orEmpty(),
                installedLanguages = getInstalledLanguages()
            )
        } else {
            AiDownloadState.Idle
        }
    }

    fun deleteAllAiAssets() {
        aiRootDir.listFiles().orEmpty().forEach { it.delete() }
        _state.value = AiDownloadState.Idle
    }

    private fun downloadResumableAsset(
        asset: AiAssetFile,
        destination: File,
        stageLabel: String
    ) {
        if (asset.url.isBlank()) {
            throw IOException("رابط تنزيل ${asset.filename} غير صالح في manifest.json")
        }

        val partFile = File(destination.parentFile, "${destination.name}.part")
        var existingBytes = if (partFile.isFile) partFile.length() else 0L

        if (asset.sizeBytes > 0L && existingBytes > asset.sizeBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        val requestBuilder = Request.Builder()
            .url(asset.url)
            .header("User-Agent", "NabdAlDhikr-Android")
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val isPartial = response.code == 206
            if (!response.isSuccessful && response.code != 416) {
                throw IOException("فشل تنزيل ${asset.filename} (HTTP ${response.code})")
            }

            if (response.code != 416) {
                val appendMode = isPartial && existingBytes > 0L
                val startOffset = if (appendMode) existingBytes else 0L
                val body = response.body ?: throw IOException("استجابة فارغة أثناء تنزيل ${asset.filename}")
                val contentLength = body.contentLength()
                val totalBytes = when {
                    asset.sizeBytes > 0L -> asset.sizeBytes
                    contentLength > 0L -> startOffset + contentLength
                    else -> -1L
                }

                body.byteStream().use { input ->
                    FileOutputStream(partFile, appendMode).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = startOffset
                        var lastEmitTime = 0L

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= PROGRESS_EMIT_INTERVAL_MS) {
                                lastEmitTime = now
                                emitDownloadProgress(stageLabel, asset.filename, downloaded, totalBytes)
                            }
                        }
                        output.flush()
                        emitDownloadProgress(stageLabel, asset.filename, downloaded, totalBytes)
                    }
                }
            }
        }

        _state.value = AiDownloadState.Verifying(asset.filename)
        if (asset.sha256.isNotBlank()) {
            val actualHash = computeSha256(partFile)
            if (!actualHash.equals(asset.sha256, ignoreCase = true)) {
                partFile.delete()
                throw IOException("فشل التحقق الأمني SHA-256 للملف ${asset.filename} — يرجى إعادة التنزيل.")
            }
        }

        if (destination.exists()) {
            destination.delete()
        }
        if (!partFile.renameTo(destination)) {
            partFile.copyTo(destination, overwrite = true)
            partFile.delete()
        }
    }

    private fun emitDownloadProgress(
        stageLabel: String,
        fileName: String,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        val progress = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        _state.value = AiDownloadState.Downloading(
            stageLabel = stageLabel,
            currentFile = fileName,
            progress = progress,
            downloadedMb = downloadedBytes / (1024f * 1024f),
            totalMb = if (totalBytes > 0L) totalBytes / (1024f * 1024f) else 0f
        )
    }

    private fun isFileValid(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (expectedSha256.isBlank()) return true
        return computeSha256(file).equals(expectedSha256, ignoreCase = true)
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeCode(raw: String): String {
        return raw.lowercase().replace(Regex("[^a-z0-9_-]"), "").ifBlank { "ar" }
    }
}
