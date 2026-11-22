package com.nabdaldhikr.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.util.Locale

/**
 * Strict hardware compatibility inspector for on-device Gemma 3 (270M) (~240MB file, ~400MB active RAM).
 *
 * Mandatory requirements (hard-blocks download if ANY check fails):
 *  1. Total RAM >= 3.0 GB (`ActivityManager.MemoryInfo.totalMem >= 3L * 1024 * 1024 * 1024`)
 *  2. Free Internal Storage >= 450 MB (`StatFs.availableBytes`)
 *  3. Android OS >= 8.0 (`API >= 26`)
 *  4. 64-bit ARM CPU (`arm64-v8a`)
 */
object DeviceCapabilityChecker {

    const val MIN_REQUIRED_RAM_BYTES: Long = 3L * 1024L * 1024L * 1024L // 3.0 GB
    const val MIN_REQUIRED_STORAGE_MB: Long = 450L
    const val MIN_REQUIRED_API_LEVEL: Int = Build.VERSION_CODES.O // API 26 (Android 8.0)

    private const val BYTES_PER_MB: Long = 1024L * 1024L
    private const val BYTES_PER_GB: Double = 1024.0 * 1024.0 * 1024.0
    private const val REQUIRED_ABI: String = "arm64-v8a"

    fun inspect(context: Context): DeviceCapabilityReport {
        val freeStorageMb = readFreeInternalStorageMb(context)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val totalRamBytes = memoryInfo.totalMem
        val isLowRam = activityManager?.isLowRamDevice ?: false
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val abis64 = Build.SUPPORTED_64_BIT_ABIS?.toList().orEmpty()
        val is64BitArm = abis64.contains(REQUIRED_ABI) || abis.contains(REQUIRED_ABI)

        return evaluateCapability(
            totalRamBytes = totalRamBytes,
            freeStorageMb = freeStorageMb,
            apiLevel = Build.VERSION.SDK_INT,
            is64BitArm = is64BitArm,
            supportedAbis = abis,
            isLowRamDevice = isLowRam
        )
    }

    /**
     * Pure deterministic evaluator separated from Android APIs for strict unit testing.
     */
    fun evaluateCapability(
        totalRamBytes: Long,
        freeStorageMb: Long,
        apiLevel: Int,
        is64BitArm: Boolean,
        supportedAbis: List<String>,
        isLowRamDevice: Boolean = false
    ): DeviceCapabilityReport {
        val totalRamGb = totalRamBytes / BYTES_PER_GB
        val ramGbLabel = String.format(Locale.US, "%.1f", totalRamGb)

        val ramOk = totalRamBytes >= MIN_REQUIRED_RAM_BYTES && !isLowRamDevice
        val storageOk = freeStorageMb >= MIN_REQUIRED_STORAGE_MB
        val apiOk = apiLevel >= MIN_REQUIRED_API_LEVEL
        val cpuOk = is64BitArm

        val checks = listOf(
            HardwareCheckItem(
                labelAr = "الذاكرة العشوائية (RAM)",
                actualValueAr = "$ramGbLabel جيجا" + if (isLowRamDevice) " (محدودة)" else "",
                requirementAr = "3.0 جيجا فأكثر",
                passed = ramOk
            ),
            HardwareCheckItem(
                labelAr = "المساحة التخزينية المتاحة",
                actualValueAr = "$freeStorageMb ميجا",
                requirementAr = "$MIN_REQUIRED_STORAGE_MB ميجا فأكثر",
                passed = storageOk
            ),
            HardwareCheckItem(
                labelAr = "نظام التشغيل (Android)",
                actualValueAr = "API $apiLevel",
                requirementAr = "Android 8.0+ (API 26)",
                passed = apiOk
            ),
            HardwareCheckItem(
                labelAr = "معالج 64-bit",
                actualValueAr = if (cpuOk) REQUIRED_ABI else (supportedAbis.firstOrNull() ?: "32-bit"),
                requirementAr = REQUIRED_ABI,
                passed = cpuOk
            )
        )

        val failedReasons = checks
            .filterNot { it.passed }
            .map { "${it.labelAr}: ${it.actualValueAr} (المطلوب ${it.requirementAr})" }

        if (failedReasons.isNotEmpty()) {
            return DeviceCapabilityReport(
                totalRamBytes = totalRamBytes,
                totalRamGb = totalRamGb,
                freeStorageMb = freeStorageMb,
                apiLevel = apiLevel,
                is64BitArm = is64BitArm,
                supportedAbis = supportedAbis,
                isLowRamDevice = isLowRamDevice,
                checks = checks,
                failedReasonsAr = failedReasons,
                status = DeviceCapabilityStatus.INCOMPATIBLE,
                statusHeadlineAr = "عذراً، جهازك لا يستوفي الحد الأدنى (3 جيجا رام و 450 ميجا مساحة)",
                statusDetailAr = "تم منع تحميل نموذج Gemma 3 (270M) لحماية جهازك من البطء أو التوقف، بينما تعمل جميع الأذكار والبحث المحلي والدرر السنية بسلاسة كاملة.",
                canDownload = false
            )
        }

        return DeviceCapabilityReport(
            totalRamBytes = totalRamBytes,
            totalRamGb = totalRamGb,
            freeStorageMb = freeStorageMb,
            apiLevel = apiLevel,
            is64BitArm = is64BitArm,
            supportedAbis = supportedAbis,
            isLowRamDevice = isLowRamDevice,
            checks = checks,
            failedReasonsAr = emptyList(),
            status = DeviceCapabilityStatus.COMPATIBLE,
            statusHeadlineAr = "جهازك متوافق تماماً ✅ (الرامات: $ramGbLabel جيجا | المساحة المتاحة: $freeStorageMb ميجا)",
            statusDetailAr = "يمكنك تحميل نموذج Gemma 3 (270M) (~240 ميجا) مجاناً من GitHub Releases، ويُحرّر ذاكرة التشغيل (~400 ميجا) تلقائياً فور إغلاق المحادثة.",
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
}
