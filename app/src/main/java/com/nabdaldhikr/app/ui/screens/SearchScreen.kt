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
import com.nabdaldhikr.app.ai.NabdAiRouter
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
    var query by remember { mutableStateOf("") }

    val tashkeelRegex = remember { Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED]") }
    fun normalize(text: String): String {
        return tashkeelRegex.replace(text, "")
            .replace('إ', 'ا').replace('أ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه')
    }

    val isFatwaBlocked = remember(query) { query.isNotBlank() && aiViewModel.isFatwaQuery(query) }
    val smartMatches = remember(categories, query) {
        if (query.isBlank() || isFatwaBlocked) emptyList() else aiViewModel.findLocalSearchMatches(query, categories)
    }

    val literalResults = remember(categories, query, isFatwaBlocked) {
        if (query.isBlank() || isFatwaBlocked) {
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
                        onValueChange = { query = it },
                        placeholder = { Text("ابحث عن ذكر أو حالة شعورية...", color = TextSecondary, fontSize = 13.sp) },
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderBg)
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
                Text("ابحث في الأذكار أو افتح نبض AI", fontSize = 16.sp, color = TextPrimary)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { navController.navigate("nabd_ai") },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryGold, contentColor = ScreenBg),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("فتح محادثة نبض AI ✨", fontWeight = FontWeight.Bold)
                }
            }
        } else if (isFatwaBlocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AiSheetBg),
                    border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = NabdAiRouter.FATWA_REFUSAL_MESSAGE_AR,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else if (literalResults.isEmpty() && smartMatches.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("لا توجد نتائج محلية مطابقة", fontSize = 16.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { navController.navigate("nabd_ai") },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryGold, contentColor = ScreenBg),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("ابحث عبر نبض AI والدرر السنية ✨", fontWeight = FontWeight.Bold)
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
                if (smartMatches.isNotEmpty()) {
                    item(key = "smart_header") {
                        Text(
                            text = "نتائج نبض AI المحلية ✨",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryGold
                        )
                    }
                    items(smartMatches, key = { "smart_${it.categoryId}_${it.dhikrId}" }) { match ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate("azkar_detail/${match.categoryId}/${match.indexInCategory}")
                                },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = AiSheetBg),
                            border = BorderStroke(1.dp, PrimaryGold.copy(alpha = 0.45f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(match.categoryName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryGold)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(match.dhikrText, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = TextPrimary)
                            }
                        }
                    }
                }

                items(literalResults, key = { "lit_${it.categoryId}_${it.azkar.id}" }) { result ->
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
                            Text(result.categoryName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryGold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(result.azkar.text, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}
