package com.nabdaldhikr.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.util.Locale

/**
 * Strict 5-point hardware compatibility inspector for Nabd Al-Dhikr offline ONNX AI.
 *
 * Hard-blocks download (`canDownload = false`) whenever ANY mandatory requirement fails,
 * preventing OOM crashes, freezes, or wasted bandwidth on incompatible devices:
 *  1. Total RAM >= 2048 MB (2 GB) and not an Android Go / low-RAM device
 *  2. Currently Available RAM >= 300 MB and OS not in lowMemory state
 *  3. Free Internal Storage >= 250 MB (model + tokenizer + language pack + safe staging)
 *  4. CPU Cores >= 4
 *  5. Supported ARM ABI (`arm64-v8a` or `armeabi-v7a`) for ONNX Runtime NEON kernels
 */
object DeviceCapabilityChecker {

    const val MIN_REQUIRED_STORAGE_MB: Long = 250L
    const val MIN_REQUIRED_TOTAL_RAM_MB: Long = 2048L
    const val MIN_REQUIRED_AVAIL_RAM_MB: Long = 300L
    const val MIN_REQUIRED_CPU_CORES: Int = 4

    private const val BYTES_PER_MB: Long = 1024L * 1024L
    private val SUPPORTED_AI_ABIS = setOf("arm64-v8a", "armeabi-v7a")

    fun inspect(context: Context): DeviceCapabilityReport {
        val freeStorageMb = readFreeInternalStorageMb(context)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val totalRamMb = memoryInfo.totalMem / BYTES_PER_MB
        val availableRamMb = memoryInfo.availMem / BYTES_PER_MB
        val isLowRam = activityManager?.isLowRamDevice ?: true
        val isLowMemoryNow = memoryInfo.lowMemory
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()

        return evaluateCapability(
            freeStorageMb = freeStorageMb,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            cpuCores = cpuCores,
            supportedAbis = abis,
            isLowRamDevice = isLowRam,
            isLowMemoryNow = isLowMemoryNow
        )
    }

    /**
     * Pure deterministic evaluator separated from Android framework APIs for strict unit testing.
     * Any single failed check strictly sets `canDownload = false` (impossible to download).
     */
    fun evaluateCapability(
        freeStorageMb: Long,
        totalRamMb: Long,
        availableRamMb: Long,
        cpuCores: Int,
        supportedAbis: List<String>,
        isLowRamDevice: Boolean,
        isLowMemoryNow: Boolean
    ): DeviceCapabilityReport {
        val ramTotalOk = totalRamMb >= MIN_REQUIRED_TOTAL_RAM_MB && !isLowRamDevice
        val ramAvailOk = availableRamMb >= MIN_REQUIRED_AVAIL_RAM_MB && !isLowMemoryNow
        val storageOk = freeStorageMb >= MIN_REQUIRED_STORAGE_MB
        val coresOk = cpuCores >= MIN_REQUIRED_CPU_CORES
        val abiOk = supportedAbis.any { it in SUPPORTED_AI_ABIS }

        val checks = listOf(
            HardwareCheckItem(
                labelAr = "الذاكرة العشوائية (RAM)",
                actualValueAr = "${formatGb(totalRamMb)} جيجابايت" + if (isLowRamDevice) " (نظام محدود)" else "",
                requirementAr = "2.0 جيجابايت فأكثر",
                passed = ramTotalOk
            ),
            HardwareCheckItem(
                labelAr = "الذاكرة المتاحة حالياً",
                actualValueAr = "$availableRamMb ميجابايت",
                requirementAr = "$MIN_REQUIRED_AVAIL_RAM_MB ميجابايت فأكثر",
                passed = ramAvailOk
            ),
            HardwareCheckItem(
                labelAr = "المساحة التخزينية الحرة",
                actualValueAr = "$freeStorageMb ميجابايت",
                requirementAr = "$MIN_REQUIRED_STORAGE_MB ميجابايت فأكثر",
                passed = storageOk
            ),
            HardwareCheckItem(
                labelAr = "أنوية المعالج (CPU)",
                actualValueAr = "$cpuCores أنوية",
                requirementAr = "$MIN_REQUIRED_CPU_CORES أنوية فأكثر",
                passed = coresOk
            ),
            HardwareCheckItem(
                labelAr = "معمارية المعالج (ARM)",
                actualValueAr = supportedAbis.firstOrNull() ?: "غير مدعومة",
                requirementAr = "arm64-v8a أو armeabi-v7a",
                passed = abiOk
            )
        )

        val failedReasons = checks
            .filterNot { it.passed }
            .map { "${it.labelAr}: المتاح ${it.actualValueAr} (المطلوب ${it.requirementAr})" }

        if (failedReasons.isNotEmpty()) {
            return DeviceCapabilityReport(
                freeStorageMb = freeStorageMb,
                totalRamMb = totalRamMb,
                availableRamMb = availableRamMb,
                cpuCores = cpuCores,
                supportedAbis = supportedAbis,
                isLowRamDevice = isLowRamDevice,
                isLowMemoryNow = isLowMemoryNow,
                checks = checks,
                failedReasonsAr = failedReasons,
                status = DeviceCapabilityStatus.INCOMPATIBLE,
                statusTitleAr = "غير متوافق — التحميل ممنوع نهائياً",
                statusDetailAr = "تم فحص عتاد جهازك وتبيّن أنه لا يدعم تشغيل نموذج الذكاء الاصطناعي بأمان، لذا تم قفل خيار التحميل تلقائياً لحماية جهازك من التوقف أو البطء الشديد.",
                canDownload = false
            )
        }

        return DeviceCapabilityReport(
            freeStorageMb = freeStorageMb,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            cpuCores = cpuCores,
            supportedAbis = supportedAbis,
            isLowRamDevice = isLowRamDevice,
            isLowMemoryNow = isLowMemoryNow,
            checks = checks,
            failedReasonsAr = emptyList(),
            status = DeviceCapabilityStatus.COMPATIBLE,
            statusTitleAr = "متوافق تماماً مع جهازك",
            statusDetailAr = "اجتاز جهازك جميع فحوصات العتاد الخمسة بنجاح؛ يمكنك الآن تحميل الذكاء الاصطناعي وتشغيله بدون إنترنت داخل التطبيق.",
            canDownload = true
        )
    }

    private fun readFreeInternalStorageMb(context: Context): Long {
        return try {
            val statFs = StatFs(context.filesDir.absolutePath)
            statFs.availableBytes / BYTES_PER_MB
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatGb(megabytes: Long): String {
        val gb = megabytes / 1024.0
        return String.format(Locale.US, "%.1f", gb)
    }
}
