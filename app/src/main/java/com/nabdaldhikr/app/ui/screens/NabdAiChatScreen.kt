package com.nabdaldhikr.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nabdaldhikr.app.ai.*
import com.nabdaldhikr.app.ui.theme.*
import com.nabdaldhikr.app.viewmodel.AiAssistantViewModel
import com.nabdaldhikr.app.viewmodel.AzkarViewModel
import java.util.Locale

private val PillGold = Color(0xFFD4AF37)
private val BlockedRed = Color(0xFFE57373)
private val BlockedCardBg = Color(0xFF331818)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NabdAiChatScreen(
    navController: NavController,
    azkarViewModel: AzkarViewModel,
    aiViewModel: AiAssistantViewModel
) {
    val categories by azkarViewModel.categories.collectAsState()
    val capability by aiViewModel.capabilityReport.collectAsState()
    val downloadState by aiViewModel.downloadState.collectAsState()
    val isGemmaInstalled by aiViewModel.isGemmaInstalled.collectAsState()
    val isAiUpdateAvailable by aiViewModel.isAiUpdateAvailable.collectAsState()
    val messages by aiViewModel.chatMessages.collectAsState()
    val isGenerating by aiViewModel.isGenerating.collectAsState()

    var inputQuery by remember { mutableStateOf("") }
    var showModelCard by remember { mutableStateOf(!isGemmaInstalled || isAiUpdateAvailable) }
    val listState = rememberLazyListState()

    // Refresh hardware check on open & release ~400MB Gemma 3 RAM immediately on screen exit
    DisposableEffect(Unit) {
        aiViewModel.refreshCapability()
        onDispose {
            aiViewModel.releaseGemmaMemory()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.AutoAwesome,
                            contentDescription = null,
                            tint = PillGold,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "نبض AI",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = PillGold
                            )
                            Text(
                                text = when {
                                    isGemmaInstalled && !isAiUpdateAvailable ->
                                        "وضع الذكاء الاصطناعي المعمّق (Gemma 3 نشط ✅)"
                                    isGemmaInstalled && isAiUpdateAvailable ->
                                        "يتوفر تحديث لنموذج Gemma 3 (${ModelDownloadManager.GEMMA_RELEASE_TAG}) • المحرك المدمج نشط"
                                    else ->
                                        "الوضع الأساسي المدمج (يعمل من موسوعة الأذكار داخل التطبيق)"
                                },
                                fontSize = 10.5.sp,
                                color = if (isGemmaInstalled && !isAiUpdateAvailable) CompletedGreen else TextSecondary,
                                maxLines = 1
                            )
                        }
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
                    TextButton(
                        onClick = { showModelCard = !showModelCard },
                        colors = ButtonDefaults.textButtonColors(contentColor = PillGold)
                    ) {
                        Text(
                            text = if (showModelCard) "إخفاء النموذج" else "Gemma 3",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderBg)
            )
        },
        bottomBar = {
            Surface(
                color = HeaderBg,
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val starterChips = remember {
                        listOf(
                            "أذكار الصباح",
                            "قلقان ومحتاج دعاء",
                            "أذكار النوم",
                            "دعاء السفر",
                            "صحة حديث سبحان الله وبحمده"
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(starterChips) { chip ->
                            SuggestionChip(
                                onClick = { aiViewModel.sendChatMessage(chip, categories) },
                                label = { Text(chip, fontSize = 11.5.sp, color = TextPrimary) },
                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = AiChipBg),
                                border = BorderStroke(1.dp, PillGold.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputQuery,
                            onValueChange = { inputQuery = it },
                            placeholder = {
                                Text(
                                    text = "اسأل عن ذكر، شعور، أو تحقق من حديث...",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PillGold,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = PillGold,
                                focusedContainerColor = AiSheetBg,
                                unfocusedContainerColor = AiSheetBg
                            )
                        )

                        FilledIconButton(
                            onClick = {
                                val q = inputQuery
                                inputQuery = ""
                                aiViewModel.sendChatMessage(q, categories)
                            },
                            enabled = inputQuery.isNotBlank() && !isGenerating,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = PillGold,
                                contentColor = ScreenBg,
                                disabledContainerColor = ProgressTrack,
                                disabledContentColor = TextSecondary
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = ScreenBg,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "إرسال"
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenGradient)
                .padding(padding)
        ) {
            // Collapsible Gemma 3 (270M) Device Compatibility & Optional Download Card
            AnimatedVisibility(visible = showModelCard) {
                Gemma3CompatibilityCard(
                    capability = capability,
                    downloadState = downloadState,
                    isInstalled = isGemmaInstalled,
                    isUpdateAvailable = isAiUpdateAvailable,
                    onRecheck = { aiViewModel.refreshCapability() },
                    onDownload = { aiViewModel.startGemmaDownload() },
                    onCancel = { aiViewModel.cancelGemmaDownload() },
                    onDelete = { aiViewModel.deleteGemmaModel() },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            // Clear Mode Pill indicating built-in fast engine vs downloaded Gemma 3 model
            val modePillBg = when {
                isGemmaInstalled && !isAiUpdateAvailable -> Color(0xFF163325)
                isGemmaInstalled && isAiUpdateAvailable -> Color(0xFF2B2412)
                else -> AiSheetBg
            }
            val modePillBorder = when {
                isGemmaInstalled && !isAiUpdateAvailable -> CompletedGreen.copy(alpha = 0.6f)
                else -> PillGold.copy(alpha = 0.45f)
            }
            val modePillText = when {
                isGemmaInstalled && !isAiUpdateAvailable ->
                    "وضع الذكاء الاصطناعي المعمّق (Gemma 3 نشط ✅)"
                isGemmaInstalled && isAiUpdateAvailable ->
                    "تنبيه: نسختك من النموذج قديمة — حدّث إلى ${ModelDownloadManager.GEMMA_RELEASE_TAG}، والمحرك المدمج يرد عليك بأمان"
                else ->
                    "الوضع الأساسي المدمج (يرد من موسوعة الأذكار داخل التطبيق بدون تحميل النموذج الإضافي)"
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clickable { showModelCard = true },
                shape = RoundedCornerShape(12.dp),
                color = modePillBg,
                border = BorderStroke(1.dp, modePillBorder)
            ) {
                Text(
                    text = modePillText,
                    color = if (isGemmaInstalled && !isAiUpdateAvailable) CompletedGreen else PillGold,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            // Conversational Chat Feed
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        onOpenDhikrForCounting = { catId, dhikrIdx ->
                            navController.navigate("azkar_detail/$catId/$dhikrIdx")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Gemma3CompatibilityCard(
    capability: DeviceCapabilityReport,
    downloadState: GemmaDownloadState,
    isInstalled: Boolean,
    isUpdateAvailable: Boolean,
    onRecheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCompatible = capability.canDownload && capability.status == DeviceCapabilityStatus.COMPATIBLE
    val accent = if (isCompatible) CompletedGreen else BlockedRed

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (isCompatible) AiSheetBg else BlockedCardBg),
        border = BorderStroke(1.2.dp, if (isCompatible) PillGold.copy(alpha = 0.55f) else BlockedRed)
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
                    text = "نموذج Gemma 3 (270M) المحلي (~240 ميجا)",
                    color = PillGold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp
                )
                IconButton(
                    onClick = onRecheck,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "إعادة فحص الجهاز",
                        tint = PillGold,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Required exact Arabic status headline with device RAM & Storage specs
            Text(
                text = capability.statusHeadlineAr,
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            Text(
                text = capability.statusDetailAr,
                color = TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 17.sp
            )

            // 4-point compact spec checklist
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                capability.checks.forEach { item ->
                    val c = if (item.passed) CompletedGreen else BlockedRed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (item.passed) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                tint = c,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${item.labelAr} (${item.requirementAr})",
                                color = TextPrimary,
                                fontSize = 11.5.sp
                            )
                        }
                        Text(
                            text = item.actualValueAr,
                            color = c,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            if (!isCompatible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BlockedRed.copy(alpha = 0.14f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = BlockedRed, modifier = Modifier.size(18.dp))
                    Text(
                        text = "تم إيقاف خيار التحميل تلقائياً لحماية جهازك — تعمل الأذكار والدرر السنية بشكل طبيعي.",
                        color = TextPrimary,
                        fontSize = 11.5.sp
                    )
                }
                return@Column
            }

            // Progress bar / Download controls for compatible devices
            when (downloadState) {
                is GemmaDownloadState.FetchingManifest -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = PillGold, strokeWidth = 2.dp)
                        Text("جاري الاتصال بـ GitHub Releases...", fontSize = 12.sp, color = TextPrimary)
                    }
                }
                is GemmaDownloadState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = PillGold,
                            trackColor = ProgressTrack
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${(downloadState.progress * 100).toInt()}% • " +
                                    String.format(Locale.US, "%.1f / %.1f MB", downloadState.downloadedMb, downloadState.totalMb),
                                color = TextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "إيقاف مؤقت",
                                color = TextSecondary,
                                fontSize = 11.5.sp,
                                modifier = Modifier.clickable { onCancel() }
                            )
                        }
                    }
                }
                is GemmaDownloadState.Verifying -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CompletedGreen, strokeWidth = 2.dp)
                        Text("جاري التحقق من سلامة الملف SHA-256...", fontSize = 12.sp, color = TextPrimary)
                    }
                }
                is GemmaDownloadState.Error -> {
                    Text(downloadState.message, color = BlockedRed, fontSize = 11.5.sp)
                    if (downloadState.canResume) {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PillGold, contentColor = ScreenBg)
                        ) {
                            Text("استكمال تحميل Gemma 3 (270M)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
                else -> {
                    if (isInstalled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isUpdateAvailable) {
                                        "يتوفر تحديث مدرب لنموذج الذكاء الاصطناعي (${ModelDownloadManager.GEMMA_RELEASE_TAG}) ✨"
                                    } else {
                                        "نموذج Gemma 3 (${ModelDownloadManager.GEMMA_RELEASE_TAG}) مثبّت وجاهز ✅"
                                    },
                                    color = if (isUpdateAvailable) PillGold else CompletedGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = onDelete) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف النموذج", tint = BlockedRed, modifier = Modifier.size(18.dp))
                                }
                            }
                            if (isUpdateAvailable) {
                                Button(
                                    onClick = onDownload,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PillGold, contentColor = ScreenBg)
                                ) {
                                    Text("تحديث نموذج الذكاء الاصطناعي (${ModelDownloadManager.GEMMA_RELEASE_TAG})", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PillGold, contentColor = ScreenBg)
                        ) {
                            Text("تحميل Gemma 3 (${ModelDownloadManager.GEMMA_RELEASE_TAG}) مجاناً", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: NabdAiChatMessage,
    onOpenDhikrForCounting: (String, Int) -> Unit
) {
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val bubbleColor = when {
        message.isUser -> HeaderBg
        message.sourceType == NabdAiSourceType.FATWA_BLOCKED -> BlockedCardBg
        message.sourceType == NabdAiSourceType.INTERNET_REQUIRED -> Color(0xFF2B2412)
        else -> AiSheetBg
    }
    val borderColor = when {
        message.isUser -> PillGold.copy(alpha = 0.5f)
        message.sourceType == NabdAiSourceType.FATWA_BLOCKED -> BlockedRed
        message.sourceType == NabdAiSourceType.INTERNET_REQUIRED -> WarningAmber
        message.sourceType == NabdAiSourceType.DORAR_VERIFIED -> CompletedGreen.copy(alpha = 0.7f)
        message.sourceType == NabdAiSourceType.CONVERSATIONAL -> CompletedGreen.copy(alpha = 0.55f)
        else -> PillGold.copy(alpha = 0.35f)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            modifier = Modifier.widthIn(max = 330.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier.padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = message.text,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )

                // Verified Source Badge
                if (!message.isUser && !message.sourceBadge.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ScreenBg.copy(alpha = 0.65f),
                        border = BorderStroke(0.8.dp, borderColor.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (message.sourceType) {
                                            NabdAiSourceType.DORAR_VERIFIED,
                                            NabdAiSourceType.CONVERSATIONAL -> CompletedGreen
                                            NabdAiSourceType.LOCAL_ADHKAR -> PillGold
                                            NabdAiSourceType.INTERNET_REQUIRED -> WarningAmber
                                            else -> BlockedRed
                                        }
                                    )
                            )
                            Text(
                                text = message.sourceBadge + if (message.usedGemma3) " • Gemma 3" else "",
                                color = if (message.sourceType == NabdAiSourceType.DORAR_VERIFIED || message.sourceType == NabdAiSourceType.CONVERSATIONAL) CompletedGreen else PillGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Direct "افتح الذكر للعد" button when a local verified Dhikr is matched
                val catId = message.linkedCategoryId
                if (!message.isUser && !catId.isNullOrBlank()) {
                    Button(
                        onClick = { onOpenDhikrForCounting(catId, message.linkedDhikrIndex) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PillGold,
                            contentColor = ScreenBg
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            text = "افتح الذكر للعد",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
