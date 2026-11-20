package com.nabdaldhikr.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nabdaldhikr.app.R
import com.nabdaldhikr.app.ai.AiQueryOutcome
import com.nabdaldhikr.app.ui.components.AiModelManagerSheet
import com.nabdaldhikr.app.ui.components.CategoryCard
import com.nabdaldhikr.app.ui.theme.*
import com.nabdaldhikr.app.viewmodel.AiAssistantViewModel
import com.nabdaldhikr.app.viewmodel.AzkarViewModel

data class CategoryItem(val id: String, val nameAr: String, val nameEn: String, val icon: ImageVector)

val categoriesList = listOf(
    CategoryItem("morning_azkar", "أذكار الصباح", "Morning Azkar", AppIcons.WbSunny),
    CategoryItem("evening_azkar", "أذكار المساء", "Evening Azkar", AppIcons.Nightlight),
    CategoryItem("sleep_azkar", "أذكار النوم", "Sleep Azkar", AppIcons.Bedtime),
    CategoryItem("wakeup_azkar", "أذكار الاستيقاظ", "Wakeup Azkar", AppIcons.Alarm),
    CategoryItem("after_prayer", "أذكار بعد الصلاة", "After Prayer Azkar", AppIcons.Mosque),
    CategoryItem("adhan_azkar", "أذكار الأذان", "Adhan Azkar", AppIcons.VolumeUp),
    CategoryItem("mosque_enter", "دعاء دخول المسجد", "Enter Mosque Dua", AppIcons.DoorFront),
    CategoryItem("mosque_exit", "دعاء الخروج من المسجد", "Exit Mosque Dua", AppIcons.DoorBack),
    CategoryItem("home_enter", "دعاء دخول المنزل", "Enter Home Dua", Icons.Outlined.Home),
    CategoryItem("home_exit", "دعاء الخروج من المنزل", "Exit Home Dua", Icons.AutoMirrored.Outlined.ExitToApp),
    CategoryItem("restroom_enter", "دعاء دخول الخلاء", "Enter Restroom Dua", AppIcons.Wc),
    CategoryItem("restroom_exit", "دعاء الخروج من الخلاء", "Exit Restroom Dua", AppIcons.CleanHands),
    CategoryItem("food_azkar", "أذكار الطعام", "Food Azkar", AppIcons.Restaurant),
    CategoryItem("travel_azkar", "أذكار السفر", "Travel Azkar", AppIcons.Flight),
    CategoryItem("clothing_azkar", "دعاء لبس الثوب", "Clothing Dua", AppIcons.Checkroom),
    CategoryItem("anger_azkar", "أذكار الغضب", "Anger Azkar", AppIcons.SentimentDissatisfied),
    CategoryItem("worry_azkar", "دعاء الهم والحزن", "Worry Dua", AppIcons.SentimentNeutral),
    CategoryItem("distress_azkar", "دعاء الكرب", "Distress Dua", AppIcons.Healing),
    CategoryItem("istikhara", "دعاء الاستخارة", "Istikhara Dua", Icons.Outlined.Star),
    CategoryItem("rain_azkar", "دعاء المطر", "Rain Dua", AppIcons.WaterDrop),
    CategoryItem("wind_azkar", "دعاء الريح", "Wind Dua", AppIcons.Air),
    CategoryItem("thunder_azkar", "ذكر الرعد", "Thunder Dua", AppIcons.Thunderstorm),
    CategoryItem("sneezing_azkar", "دعاء العطاس", "Sneezing Dua", AppIcons.Sick),
    CategoryItem("quran_duas", "أدعية قرآنية", "Quran Duas", AppIcons.MenuBook),
    CategoryItem("ruqyah", "الرقية الشرعية", "Ruqyah", AppIcons.Shield),
    CategoryItem("general_azkar", "أذكار متنوعة", "General Azkar", AppIcons.AutoAwesome),
    CategoryItem("market_dua", "دعاء السوق", "Market Dua", AppIcons.Storefront),
    CategoryItem("sick_dua", "دعاء المريض", "Sick Dua", AppIcons.LocalHospital)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    azkarViewModel: AzkarViewModel,
    aiViewModel: AiAssistantViewModel
) {
    val counters by azkarViewModel.counters.collectAsState()
    val allCategories by azkarViewModel.categories.collectAsState()
    val homeSearchOutcome by aiViewModel.homeSearchOutcome.collectAsState()
    val isNeuralReady by aiViewModel.isNeuralModelReady.collectAsState()
    val capability by aiViewModel.capabilityReport.collectAsState()
    var smartQuery by remember { mutableStateOf("") }
    var showModelManager by remember { mutableStateOf(false) }

    // نبض خفيف للأزرار العائمة
    val infiniteTransition = rememberInfiniteTransition(label = "FabPulse")
    val fabScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FabScale"
    )

    // حساب إجمالي الإنجاز
    val totalCompleted = remember(counters) {
        categoriesList.sumOf { azkarViewModel.getCategoryProgress(it.id, counters).first }
    }
    val totalCount = remember(counters) {
        categoriesList.sumOf { azkarViewModel.getCategoryProgress(it.id, counters).second }
    }

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = { 
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "شعار التطبيق",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .border(1.dp, PrimaryGold.copy(alpha = 0.5f), CircleShape)
                        )
                        Text(
                            text = "نبض الذكر", 
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                            color = PrimaryGold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("search") }) {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = "بحث", tint = TextPrimary)
                    }
                    IconButton(onClick = { navController.navigate("favorites") }) {
                        Icon(imageVector = Icons.Filled.Favorite, contentDescription = "المفضلة", tint = PrimaryGold)
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "الإعدادات", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeaderBg
                )
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // على اليمين (بداية السطر في RTL): زر القبلة الذكية
                FloatingActionButton(
                    onClick = { navController.navigate("qibla") },
                    shape = RoundedCornerShape(24.dp),
                    containerColor = CardBg,
                    contentColor = PrimaryGold,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                        }
                        .border(1.5.dp, PrimaryGold, RoundedCornerShape(24.dp))
                        .shadow(12.dp, RoundedCornerShape(24.dp), spotColor = PrimaryGold)
                ) {
                    // أيقونة بوصلة القبلة مع رمز الكعبة
                    Canvas(modifier = Modifier.size(28.dp)) {
                        val r = size.minDimension / 2.2f
                        drawCircle(
                            color = PrimaryGold,
                            radius = r,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        // إبرة البوصلة الماسية باتجاه الأعلى
                        val needlePath = Path().apply {
                            moveTo(center.x, center.y - r * 0.72f)
                            lineTo(center.x + r * 0.24f, center.y)
                            lineTo(center.x, center.y + r * 0.55f)
                            lineTo(center.x - r * 0.24f, center.y)
                            close()
                        }
                        drawPath(needlePath, color = PrimaryGold.copy(alpha = 0.85f))
                        // مربع الكعبة في المنتصف
                        val kSize = 6.dp.toPx()
                        drawRoundRect(
                            color = ScreenBg,
                            topLeft = Offset(center.x - kSize / 2f, center.y - kSize / 2f),
                            size = Size(kSize, kSize),
                            cornerRadius = CornerRadius(1.5.dp.toPx())
                        )
                    }
                }

                // على الشمال (نهاية السطر في RTL): زر المسبحة
                FloatingActionButton(
                    onClick = { navController.navigate("tasbih") },
                    shape = RoundedCornerShape(24.dp),
                    containerColor = PrimaryGold,
                    contentColor = ScreenBg,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                        }
                        .shadow(12.dp, RoundedCornerShape(24.dp), spotColor = PrimaryGold)
                ) {
                    // دائرة بنقطة داكنة
                    Canvas(modifier = Modifier.size(26.dp)) {
                        drawCircle(
                            color = ScreenBg,
                            radius = size.minDimension / 2.3f,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                        drawCircle(
                            color = ScreenBg,
                            radius = 3.5.dp.toPx()
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenGradient)
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // بانر إيماني وإحصائي متطور في أعلى الصفحة
            item(span = { GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.25f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardGradient)
                            .padding(18.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "﴿ أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ ﴾",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryGold
                                )
                                Text(
                                    text = "$totalCompleted / $totalCount",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (totalCompleted > 0) CompletedGreen else TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            // شريط تقدم الإنجاز العام
                            val generalProgress = if (totalCount > 0) totalCompleted.toFloat() / totalCount.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { generalProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (generalProgress >= 1f) CompletedGreen else PrimaryGold,
                                trackColor = ProgressTrack
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (totalCompleted == 0) "ابدأ وردك اليومي بنبض قلبك بالذكر"
                                       else if (totalCompleted < totalCount) "واصل ذكر الله، تقبل الله طاعتك"
                                       else "مبارك! أتممت أذكار اليوم كاملاً",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // بطاقة البحث الدلالي الذكي (Smart Semantic Search) على الشاشة الرئيسية
            item(span = { GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = AiSheetBg),
                    border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.4f))
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = AppIcons.AutoAwesome,
                                    contentDescription = "البحث الذكي",
                                    tint = PrimaryGold,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "البحث الذكي بالأذكار ✨",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryGold
                                )
                            }
                            Surface(
                                modifier = Modifier.clickable { showModelManager = true },
                                shape = RoundedCornerShape(10.dp),
                                color = AiChipBg,
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        isNeuralReady -> CompletedGreen.copy(alpha = 0.6f)
                                        !capability.canDownload -> androidx.compose.ui.graphics.Color(0xFFE57373).copy(alpha = 0.7f)
                                        else -> PrimaryGold.copy(alpha = 0.45f)
                                    }
                                )
                            ) {
                                Text(
                                    text = when {
                                        isNeuralReady -> "ONNX نشط ✓"
                                        !capability.canDownload -> "الجهاز غير متوافق"
                                        else -> "فحص وتحميل AI"
                                    },
                                    color = when {
                                        isNeuralReady -> CompletedGreen
                                        !capability.canDownload -> androidx.compose.ui.graphics.Color(0xFFE57373)
                                        else -> PrimaryGold
                                    },
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = smartQuery,
                            onValueChange = { updated ->
                                smartQuery = updated
                                aiViewModel.runSemanticSearch(updated, allCategories)
                            },
                            placeholder = {
                                Text(
                                    text = "اسأل بمشاعرك أو حالك (مثلاً: قلقان، أرق، مسافر)...",
                                    color = TextSecondary,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = PrimaryGold, modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                if (smartQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        smartQuery = ""
                                        aiViewModel.clearHomeSearch()
                                    }) {
                                        Icon(Icons.Filled.Close, contentDescription = "مسح", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
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

                        if (smartQuery.isBlank()) {
                            val quickEmotions = remember {
                                listOf("قلقان ومهموم", "مش عارف أنام", "طالع أسافر", "دعاء لمريض", "الرقية من الحسد")
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(quickEmotions) { prompt ->
                                    SuggestionChip(
                                        onClick = {
                                            smartQuery = prompt
                                            aiViewModel.runSemanticSearch(prompt, allCategories)
                                        },
                                        label = { Text(prompt, fontSize = 11.5.sp, color = TextPrimary) },
                                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = AiChipBg),
                                        border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.3f)),
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                }
                            }
                        } else {
                            when (val outcome = homeSearchOutcome) {
                                is AiQueryOutcome.Match -> {
                                    val displayMatches = listOf(outcome.bestMatch) + outcome.alternatives.take(2)
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        displayMatches.forEach { match ->
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        val cat = azkarViewModel.getCategory(match.categoryId)
                                                        val idx = cat?.azkarList?.indexOfFirst { it.id == match.dhikrId }
                                                            ?.takeIf { it >= 0 } ?: (match.orderIndex - 1).coerceAtLeast(0)
                                                        if (match.categoryId.isNotBlank()) {
                                                            navController.navigate("azkar_detail/${match.categoryId}/$idx")
                                                        }
                                                    },
                                                shape = RoundedCornerShape(12.dp),
                                                color = CardBg,
                                                border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.35f))
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = match.categoryName,
                                                            color = PrimaryGold,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 12.sp
                                                        )
                                                        Text(
                                                            text = "${(match.similarityScore * 100).toInt()}% • ${outcome.latencyMs}ms",
                                                            color = CompletedGreen,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = match.dhikrText,
                                                        color = TextPrimary,
                                                        fontSize = 14.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (match.shortVirtue.isNotBlank()) {
                                                        Spacer(modifier = Modifier.height(3.dp))
                                                        Text(
                                                            text = match.shortVirtue,
                                                            color = TextSecondary,
                                                            fontSize = 11.5.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                is AiQueryOutcome.BlockedOutOfScope -> {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        color = HeaderBg,
                                        border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.35f))
                                    ) {
                                        Text(
                                            text = outcome.message,
                                            color = TextPrimary,
                                            fontSize = 12.5.sp,
                                            lineHeight = 19.sp,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }

            items(categoriesList, key = { it.id }) { item ->
                val progress = azkarViewModel.getCategoryProgress(item.id, counters)
                CategoryCard(
                    item = item,
                    progress = progress,
                    onClick = { navController.navigate("azkar/${item.id}") }
                )
            }
        }
    }

    if (showModelManager) {
        AiModelManagerSheet(
            aiViewModel = aiViewModel,
            onDismiss = { showModelManager = false }
        )
    }
}
