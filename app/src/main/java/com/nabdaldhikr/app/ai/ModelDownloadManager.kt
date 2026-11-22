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
 * 100% Free GitHub Releases Resumable Downloader for the ~240MB on-device Gemma 3 (270M) model.
 *
 * Security & Resilience:
 *  - Hard-blocks download if `DeviceCapabilityChecker` fails (min 3GB RAM, 450MB storage, API 26+, arm64-v8a).
 *  - Uses OkHttp + Kotlin Coroutines with HTTP `Range` resume via `.part` staging.
 *  - Verifies SHA-256 checksum (when provided in `gemma_manifest.json`) before atomic activation.
 */
class ModelDownloadManager(
    private val context: Context,
    private val gson: Gson = Gson(),
    private val client: OkHttpClient = defaultOkHttpClient()
) {
    companion object {
        private const val GITHUB_OWNER = "omar15912007-hash"
        private const val GITHUB_REPO = "Nabd-Al-Dhikr"
        private const val GEMMA_RELEASE_TAG = "ai-v1.0.2"

        const val GEMMA_MODEL_FILENAME = "gemma3-270m-nabd.task"
        const val DEFAULT_MANIFEST_URL =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$GEMMA_RELEASE_TAG/gemma_manifest.json"
        const val DEFAULT_DIRECT_MODEL_URL =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$GEMMA_RELEASE_TAG/$GEMMA_MODEL_FILENAME"

        private const val BUFFER_BYTES = 32 * 1024
        private const val PROGRESS_EMIT_INTERVAL_MS = 120L

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val modelDir: File
        get() = File(context.filesDir, "nabd_gemma").apply { if (!exists()) mkdirs() }

    private val _state = MutableStateFlow<GemmaDownloadState>(GemmaDownloadState.Idle)
    val state: StateFlow<GemmaDownloadState> = _state.asStateFlow()

    fun modelFile(): File = File(modelDir, GEMMA_MODEL_FILENAME)

    fun isGemmaInstalled(): Boolean = modelFile().isFile && modelFile().length() > 1_000_000L

    fun installedModelSizeMb(): Float {
        val f = modelFile()
        return if (f.isFile) f.length() / (1024f * 1024f) else 0f
    }

    suspend fun fetchManifest(manifestUrl: String = DEFAULT_MANIFEST_URL): Result<GemmaManifest> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(manifestUrl)
                    .header("User-Agent", "NabdAlDhikr-Android")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("HTTP ${response.code}")
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withContext Result.failure(IOException("Empty manifest"))
                    }
                    Result.success(gson.fromJson(body, GemmaManifest::class.java))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Downloads Gemma 3 (270M) `.task` model into internal app storage with progress, resume,
     * and SHA-256 verification. Strictly blocked on incompatible devices.
     */
    suspend fun downloadGemma3Model(
        manifestUrl: String = DEFAULT_MANIFEST_URL
    ): Result<File> = withContext(Dispatchers.IO) {
        val capability = DeviceCapabilityChecker.inspect(context)
        if (!capability.canDownload) {
            val blocked = capability.statusHeadlineAr
            _state.value = GemmaDownloadState.Error(blocked, canResume = false)
            return@withContext Result.failure(UnsupportedOperationException(blocked))
        }

        _state.value = GemmaDownloadState.FetchingManifest
        val manifest = fetchManifest(manifestUrl).getOrElse {
            // Clean fallback to direct GitHub Release asset URL if manifest is missing
            GemmaManifest(
                filename = GEMMA_MODEL_FILENAME,
                url = DEFAULT_DIRECT_MODEL_URL
            )
        }

        val targetUrl = manifest.url.ifBlank { DEFAULT_DIRECT_MODEL_URL }
        val destination = modelFile()

        try {
            if (isFileValid(destination, manifest.sha256)) {
                _state.value = GemmaDownloadState.Ready(
                    modelPath = destination.absolutePath,
                    sizeMb = installedModelSizeMb()
                )
                return@withContext Result.success(destination)
            }

            downloadResumable(
                url = targetUrl,
                expectedSizeBytes = manifest.sizeBytes,
                expectedSha256 = manifest.sha256,
                destination = destination
            )

            _state.value = GemmaDownloadState.Ready(
                modelPath = destination.absolutePath,
                sizeMb = installedModelSizeMb()
            )
            Result.success(destination)
        } catch (ce: CancellationException) {
            _state.value = GemmaDownloadState.Idle
            throw ce
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "تعذر إكمال التحميل من GitHub Releases؛ يمكنك الاستكمال لاحقاً."
            _state.value = GemmaDownloadState.Error(message = msg, canResume = true)
            Result.failure(e)
        }
    }

    fun deleteGemmaModel() {
        modelDir.listFiles().orEmpty().forEach { it.delete() }
        _state.value = GemmaDownloadState.Idle
    }

    private fun downloadResumable(
        url: String,
        expectedSizeBytes: Long,
        expectedSha256: String,
        destination: File
    ) {
        val partFile = File(destination.parentFile, "${destination.name}.part")
        var existingBytes = if (partFile.isFile) partFile.length() else 0L

        if (expectedSizeBytes > 0L && existingBytes > expectedSizeBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "NabdAlDhikr-Android")
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val isPartial = response.code == 206
            if (!response.isSuccessful && response.code != 416) {
                throw IOException("تعذر تنزيل نموذج Gemma 3 (270M) من GitHub Releases (HTTP ${response.code}). تأكد من نشر ملف $GEMMA_MODEL_FILENAME تحت وسم $GEMMA_RELEASE_TAG.")
            }

            if (response.code != 416) {
                val appendMode = isPartial && existingBytes > 0L
                val startOffset = if (appendMode) existingBytes else 0L
                val body = response.body ?: throw IOException("استجابة فارغة من الخادم أثناء التحميل.")
                val contentLength = body.contentLength()
                val totalBytes = when {
                    contentLength > 0L -> startOffset + contentLength
                    expectedSizeBytes > 0L -> expectedSizeBytes
                    else -> -1L
                }

                body.byteStream().use { input ->
                    FileOutputStream(partFile, appendMode).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = startOffset
                        var lastEmit = 0L

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                                lastEmit = now
                                emitProgress(downloaded, totalBytes)
                            }
                        }
                        output.flush()
                        emitProgress(downloaded, totalBytes)
                    }
                }
            }
        }

        _state.value = GemmaDownloadState.Verifying
        if (expectedSha256.isNotBlank()) {
            val actualHash = computeSha256(partFile)
            if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                partFile.delete()
                throw IOException("فشل التحقق الأمني SHA-256 لملف Gemma 3 — يرجى إعادة المحاولة.")
            }
        }

        if (destination.exists()) destination.delete()
        if (!partFile.renameTo(destination)) {
            partFile.copyTo(destination, overwrite = true)
            partFile.delete()
        }
    }

    private fun emitProgress(downloadedBytes: Long, totalBytes: Long) {
        val progress = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        _state.value = GemmaDownloadState.Downloading(
            progress = progress,
            downloadedMb = downloadedBytes / (1024f * 1024f),
            totalMb = if (totalBytes > 0L) totalBytes / (1024f * 1024f) else 240f
        )
    }

    private fun isFileValid(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() <= 1_000_000L) return false
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
}
