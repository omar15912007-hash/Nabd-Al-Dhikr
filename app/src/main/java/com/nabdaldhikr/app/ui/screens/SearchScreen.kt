package com.nabdaldhikr.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nabdaldhikr.app.ai.AiQueryOutcome
import com.nabdaldhikr.app.data.model.Azkar
import com.nabdaldhikr.app.ui.theme.*
import com.nabdaldhikr.app.viewmodel.AiAssistantViewModel
import com.nabdaldhikr.app.viewmodel.AzkarViewModel

private data class SearchResult(
    val categoryId: String,
    val categoryName: String,
    val azkar: Azkar,
    val indexInCategory: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    azkarViewModel: AzkarViewModel,
    aiViewModel: AiAssistantViewModel
) {
    val categories by azkarViewModel.categories.collectAsState()
    val aiOutcome by aiViewModel.homeSearchOutcome.collectAsState()
    var query by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { aiViewModel.clearHomeSearch() }
    }

    // تجريد التشكيل وتطبيع الهمزات للبحث العربي
    val tashkeelRegex = remember { Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED]") }
    fun normalize(text: String): String {
        return tashkeelRegex.replace(text, "")
            .replace('إ', 'ا').replace('أ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه')
    }

    val results = remember(categories, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            val q = normalize(query.trim())
            buildList {
                categories.forEach { category ->
                    category.azkarList.forEachIndexed { index, azkar ->
                        if (normalize(azkar.text).contains(q) || normalize(azkar.source).contains(q)) {
                            add(SearchResult(category.id, category.nameAr, azkar, index))
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { updated ->
                            query = updated
                            aiViewModel.runSemanticSearch(updated, categories)
                        },
                        placeholder = { Text("ابحث بذكر أو شعور (مثلاً: قلقان، سفر)...", color = TextSecondary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = PrimaryGold) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryGold,
                            unfocusedBorderColor = ProgressTrack,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = PrimaryGold
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeaderBg
                )
            )
        }
    ) { padding ->
        if (query.isBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = TextSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "ابحث في الأذكار",
                    fontSize = 16.sp,
                    color = TextPrimary
                )
            }
        } else if (results.isEmpty() && aiOutcome !is AiQueryOutcome.Match) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val blocked = aiOutcome as? AiQueryOutcome.BlockedOutOfScope
                if (blocked != null) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AiSheetBg),
                        border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = blocked.message,
                            fontSize = 14.sp,
                            color = TextPrimary,
                            lineHeight = 22.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Text(
                        text = "لا توجد نتائج",
                        fontSize = 16.sp,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val semanticMatchOutcome = aiOutcome as? AiQueryOutcome.Match
                if (semanticMatchOutcome != null) {
                    item(key = "ai_semantic_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = AppIcons.AutoAwesome,
                                contentDescription = null,
                                tint = PrimaryGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "نتائج البحث الدلالي الذكي ✨ (${semanticMatchOutcome.latencyMs}ms)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryGold
                            )
                        }
                    }
                    val aiMatches = listOf(semanticMatchOutcome.bestMatch) + semanticMatchOutcome.alternatives
                    items(aiMatches, key = { "ai_${it.categoryId}_${it.dhikrId}" }) { match ->
                        Card(
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
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = AiSheetBg),
                            border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = match.categoryName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryGold
                                    )
                                    Text(
                                        text = "تطابق ${(match.similarityScore * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        color = CompletedGreen
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = match.dhikrText,
                                    fontSize = 15.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    color = TextPrimary
                                )
                                if (match.shortVirtue.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = match.shortVirtue,
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                items(results, key = { "${it.categoryId}_${it.azkar.id}" }) { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate("azkar_detail/${result.categoryId}/${result.indexInCategory}")
                            },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = result.categoryName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryGold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = result.azkar.text,
                                fontSize = 16.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}