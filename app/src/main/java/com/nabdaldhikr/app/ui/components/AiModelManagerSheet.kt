package com.nabdaldhikr.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nabdaldhikr.app.ai.AiDownloadState
import com.nabdaldhikr.app.ai.DeviceCapabilityStatus
import com.nabdaldhikr.app.ui.theme.*
import com.nabdaldhikr.app.viewmodel.AiAssistantViewModel
import java.util.Locale

private val BlockedRed = Color(0xFFE57373)
private val BlockedCardBg = Color(0xFF331818)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelManagerSheet(
    aiViewModel: AiAssistantViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val capability by aiViewModel.capabilityReport.collectAsState()
    val downloadState by aiViewModel.downloadState.collectAsState()
    val installedLanguages by aiViewModel.installedLanguages.collectAsState()
    val activeLanguage by aiViewModel.activeLanguageCode.collectAsState()
    val languages by aiViewModel.availableLanguages.collectAsState()

    var selectedCode by remember { mutableStateOf(activeLanguage) }

    LaunchedEffect(Unit) {
        aiViewModel.refreshCapability()
        if (capability.canDownload) {
            aiViewModel.refreshManifestLanguages()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AiSheetBg,
        contentColor = TextPrimary,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = PrimaryGold.copy(alpha = 0.6f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.AutoAwesome,
                        contentDescription = null,
                        tint = PrimaryGold,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "الذكاء الاصطناعي داخل التطبيق",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryGold
                        )
                        Text(
                            text = "فحص عتاد الجهاز والتحميل المباشر بدون إنترنت",
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = TextSecondary)
                }
            }

            // 5-Point Hardware Inspection Card
            val isCompatible = capability.canDownload && capability.status == DeviceCapabilityStatus.COMPATIBLE
            val badgeColor = if (isCompatible) CompletedGreen else BlockedRed

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = if (isCompatible) CardBg else BlockedCardBg),
                border = BorderStroke(1.2.dp, badgeColor.copy(alpha = 0.7f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "نتيجة فحص عتاد جهازك",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = badgeColor.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, badgeColor)
                        ) {
                            Text(
                                text = if (isCompatible) "متوافق تماماً" else "غير متوافق",
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = capability.statusDetailAr,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    HorizontalDivider(color = badgeColor.copy(alpha = 0.2f))

                    // Itemized 5 Hardware Checks
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        capability.checks.forEach { item ->
                            val itemColor = if (item.passed) CompletedGreen else BlockedRed
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(itemColor.copy(alpha = 0.18f))
                                            .border(1.dp, itemColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (item.passed) Icons.Filled.Check else Icons.Filled.Close,
                                            contentDescription = null,
                                            tint = itemColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = item.labelAr,
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "المطلوب: ${item.requirementAr}",
                                            color = TextSecondary,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                }
                                Text(
                                    text = item.actualValueAr,
                                    color = itemColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            aiViewModel.refreshCapability()
                            if (aiViewModel.capabilityReport.value.canDownload) {
                                aiViewModel.refreshManifestLanguages()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = PrimaryGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إعادة فحص الجهاز الآن", color = PrimaryGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // STRICT HARD BLOCK UI when device fails ANY hardware check
            if (!isCompatible) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BlockedCardBg),
                    border = BorderStroke(1.5.dp, BlockedRed)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(BlockedRed.copy(alpha = 0.18f))
                                .border(1.dp, BlockedRed, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "التحميل مقفل",
                                tint = BlockedRed,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "تم منع تحميل الذكاء الاصطناعي نهائياً على هذا الجهاز",
                            color = BlockedRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "لا يمكن تنزيل نموذج الذكاء الاصطناعي العصبي على الأجهزة التي لا تستوفي المتطلبات أعلاه لحمايتها من التهنيج أو استنزاف الذاكرة. " +
                                "يمكنك الاستمرار في استخدام جميع الأذكار المحققة، والشرح الميسّر، ومعاني الكلمات، والبحث الخفيف بسرعة فائقة دون أي تحميل إضافي.",
                            color = TextPrimary,
                            fontSize = 12.5.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                return@Column
            }

            // =========================================================================
            // COMPATIBLE DEVICE ONLY: In-App Downloader + Progress + Language Selector
            // =========================================================================
            when (val state = downloadState) {
                is AiDownloadState.FetchingManifest -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = PrimaryGold,
                            strokeWidth = 2.5.dp
                        )
                        Text("جاري جلب قائمة الحزم المتاحة من الخادم...", fontSize = 13.sp, color = TextPrimary)
                    }
                }
                is AiDownloadState.Downloading -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = HeaderBg),
                        border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(state.stageLabel, color = PrimaryGold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = PrimaryGold,
                                trackColor = ProgressTrack
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${(state.progress * 100).toInt()}%",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.1f / %.1f MB",
                                        state.downloadedMb,
                                        state.totalMb
                                    ),
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(
                                onClick = { aiViewModel.cancelDownload() },
                                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                            ) {
                                Text("إيقاف مؤقت (يمكن الاستكمال لاحقاً)", fontSize = 12.sp)
                            }
                        }
                    }
                }
                is AiDownloadState.Verifying -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = CompletedGreen,
                            strokeWidth = 2.5.dp
                        )
                        Text("جاري التحقق الأمني SHA-256 للملف: ${state.fileName}", fontSize = 13.sp, color = TextPrimary)
                    }
                }
                is AiDownloadState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = BlockedCardBg),
                        border = BorderStroke(1.dp, BlockedRed)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.message, color = TextPrimary, fontSize = 12.5.sp)
                            if (state.canResume) {
                                Button(
                                    onClick = { aiViewModel.startModelDownload(selectedCode) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryGold,
                                        contentColor = ScreenBg
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("استكمال التحميل", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                is AiDownloadState.Ready -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = CompletedGreen.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, CompletedGreen)
                    ) {
                        Text(
                            text = "تم تثبيت الذكاء الاصطناعي وحزمة اللغة (${state.languageCode}) داخل التطبيق بنجاح ويعمل الآن بدون إنترنت ✨",
                            color = TextPrimary,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                AiDownloadState.Idle -> Unit
            }

            // Language Selector
            Text(
                text = "اختر لغة حزمة الذكاء الاصطناعي",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryGold
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(languages, key = { it.code }) { lang ->
                    val isSelected = selectedCode == lang.code
                    val isInstalled = installedLanguages.contains(lang.code)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedCode = lang.code
                                if (isInstalled) {
                                    aiViewModel.setActiveLanguage(lang.code)
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) HeaderBg else AiChipBg,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) PrimaryGold else CardBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${lang.nativeName} (${lang.code.uppercase(Locale.ROOT)})",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                                Text(
                                    text = if (isInstalled) "مثبّت داخل التطبيق ويعمل بدون إنترنت" else lang.englishName,
                                    color = if (isInstalled) CompletedGreen else TextSecondary,
                                    fontSize = 11.5.sp
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isInstalled) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(CompletedGreen.copy(alpha = 0.2f))
                                            .border(1.dp, CompletedGreen, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "مثبت",
                                            tint = CompletedGreen,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    IconButton(onClick = { aiViewModel.deleteLanguagePack(lang.code) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "حذف الحزمة",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Primary In-App Download Button
            val isBusy = downloadState is AiDownloadState.Downloading ||
                downloadState is AiDownloadState.FetchingManifest ||
                downloadState is AiDownloadState.Verifying

            Button(
                onClick = { aiViewModel.startModelDownload(selectedCode) },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryGold,
                    contentColor = ScreenBg,
                    disabledContainerColor = ProgressTrack,
                    disabledContentColor = TextSecondary
                )
            ) {
                Text(
                    text = if (installedLanguages.contains(selectedCode)) {
                        "تحديث / التحقق من حزمة ($selectedCode) داخل التطبيق"
                    } else {
                        "تحميل الذكاء الاصطناعي وحزمة ($selectedCode) الآن"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            if (installedLanguages.isNotEmpty()) {
                OutlinedButton(
                    onClick = { aiViewModel.deleteAllModels() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BlockedRed.copy(alpha = 0.6f))
                ) {
                    Text("إزالة ملفات الذكاء الاصطناعي وتوفير المساحة", color = BlockedRed, fontSize = 12.5.sp)
                }
            }
        }
    }
}
