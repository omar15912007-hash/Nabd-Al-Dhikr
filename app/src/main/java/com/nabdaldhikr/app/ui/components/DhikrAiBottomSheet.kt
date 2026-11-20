package com.nabdaldhikr.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nabdaldhikr.app.ai.*
import com.nabdaldhikr.app.data.model.Azkar
import com.nabdaldhikr.app.data.model.AzkarCategory
import com.nabdaldhikr.app.ui.theme.*
import com.nabdaldhikr.app.viewmodel.AiAssistantViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DhikrAiBottomSheet(
    categoryId: String,
    categoryName: String,
    azkar: Azkar,
    allCategories: List<AzkarCategory>,
    aiViewModel: AiAssistantViewModel,
    onOpenModelManager: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val metadataMap by aiViewModel.offlineMetadata.collectAsState()
    val isNeuralReady by aiViewModel.isNeuralModelReady.collectAsState()
    val capability by aiViewModel.capabilityReport.collectAsState()
    val downloadState by aiViewModel.downloadState.collectAsState()
    val sheetAnswer by aiViewModel.sheetAnswer.collectAsState()
    val answerLatencyMs by aiViewModel.sheetAnswerLatencyMs.collectAsState()

    val metadata = remember(metadataMap, categoryId, azkar.id) {
        metadataMap["${categoryId}_${azkar.id}"]
    }
    var customQuestion by remember(azkar.id) { mutableStateOf("") }

    DisposableEffect(azkar.id) {
        aiViewModel.clearSheetAnswer()
        onDispose { aiViewModel.clearSheetAnswer() }
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
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(PrimaryGold.copy(alpha = 0.16f))
                            .border(1.dp, PrimaryGold.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.AutoAwesome,
                            contentDescription = "المساعد الذكي",
                            tint = PrimaryGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "مساعد نبض الذكر الذكي ✨",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryGold
                        )
                        Text(
                            text = if (isNeuralReady) {
                                "يعمل بنموذج ONNX المحلي بدون إنترنت"
                            } else {
                                "الوضع المحلي الفوري • بدون فتاوى"
                            },
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "إغلاق",
                        tint = TextSecondary
                    )
                }
            }

            HorizontalDivider(color = PrimaryGold.copy(alpha = 0.2f))

            // 1. Simplified Explanation
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.25f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "الشرح الميسّر للذكر",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryGold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = metadata?.detailedExplanation?.ifBlank { azkar.virtue }
                            ?: azkar.virtue.ifBlank { "ذكر نبوي صحيح من $categoryName يُستحب قوله بحضور قلب وطمأنينة." },
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = TextPrimary
                    )
                }
            }

            // 2. Word Meanings (Vocabulary)
            val vocabulary = metadata?.vocabulary.orEmpty()
            if (vocabulary.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "معاني الكلمات الصعبة",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryGold
                    )
                    vocabulary.forEach { entry ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = AiChipBg,
                            border = BorderStroke(1.dp, CardBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = PrimaryGold.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = entry.word,
                                        color = PrimaryGold,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Text(
                                    text = entry.meaning,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Clickable Suggested Follow-ups Chips (< 6 words each)
            val followups = metadata?.suggestedFollowups?.takeIf { it.isNotEmpty() }
                ?: listOf("ما فضل هذا الذكر؟", "كم مرة يُقال؟", "ما مصدر هذا الذكر؟")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "أسئلة سريعة مقترحة",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryGold
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(followups) { chipQuestion ->
                        SuggestionChip(
                            onClick = {
                                aiViewModel.askAboutCurrentDhikr(
                                    question = chipQuestion,
                                    categoryId = categoryId,
                                    categoryName = categoryName,
                                    azkar = azkar,
                                    allCategories = allCategories
                                )
                            },
                            label = {
                                Text(
                                    text = chipQuestion,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = AiChipBg
                            ),
                            border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }
            }

            // 4. Interactive Answer Box
            AnimatedVisibility(visible = !sheetAnswer.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = HeaderBg),
                    border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "إجابة المساعد",
                                color = PrimaryGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            if (answerLatencyMs > 0L) {
                                Text(
                                    text = "${answerLatencyMs}ms",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = sheetAnswer.orEmpty(),
                            color = TextPrimary,
                            fontSize = 13.5.sp,
                            lineHeight = 21.sp
                        )
                    }
                }
            }

            // 5. Free-text question input (guarded against fatwas + 0.65 similarity gate)
            OutlinedTextField(
                value = customQuestion,
                onValueChange = { customQuestion = it },
                placeholder = {
                    Text(
                        text = "اسأل عن معنى كلمة أو فضل أو توقيت الذكر...",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    IconButton(
                        enabled = customQuestion.isNotBlank(),
                        onClick = {
                            val q = customQuestion
                            customQuestion = ""
                            aiViewModel.askAboutCurrentDhikr(
                                question = q,
                                categoryId = categoryId,
                                categoryName = categoryName,
                                azkar = azkar,
                                allCategories = allCategories
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "إرسال",
                            tint = if (customQuestion.isNotBlank()) PrimaryGold else TextSecondary
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryGold,
                    unfocusedBorderColor = CardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = PrimaryGold,
                    focusedContainerColor = ScreenBg.copy(alpha = 0.5f),
                    unfocusedContainerColor = ScreenBg.copy(alpha = 0.35f)
                )
            )

            // 6. Optional Offline Neural Model Status / In-App Download Banner
            if (!isNeuralReady) {
                val isCompatible = capability.canDownload && capability.status == DeviceCapabilityStatus.COMPATIBLE
                val statusColor = if (isCompatible) CompletedGreen else Color(0xFFE57373)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenModelManager() },
                    shape = RoundedCornerShape(14.dp),
                    color = ScreenBg.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isCompatible) {
                                    "تحميل الذكاء الاصطناعي داخل التطبيق (50+ لغة)"
                                } else {
                                    "فحص توافق الجهاز مع الذكاء الاصطناعي"
                                },
                                color = PrimaryGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = if (isCompatible) "متوافق" else "ممنوع التحميل",
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = when {
                                !isCompatible -> "جهازك غير متوافق مع نموذج الذكاء الاصطناعي العصبي؛ تم قفل التحميل لحماية جهازك وتعمل الشروحات الخفيفة بشكل طبيعي."
                                downloadState is AiDownloadState.Downloading -> {
                                    val st = downloadState as AiDownloadState.Downloading
                                    "${st.stageLabel} (${(st.progress * 100).toInt()}%)"
                                }
                                downloadState is AiDownloadState.Verifying -> {
                                    val st = downloadState as AiDownloadState.Verifying
                                    "جاري التحقق من SHA-256 للملف ${st.fileName}..."
                                }
                                else -> "جهازك متوافق تماماً؛ اضغط هنا لفحص العتاد وتحميل النموذج مباشرة من داخل التطبيق."
                            },
                            color = TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        }
    }
}
