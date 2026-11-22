package com.nabdaldhikr.app.ai

import com.nabdaldhikr.app.data.model.AzkarCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Smart Hybrid Router for "Nabd AI" (نبض AI) — 100% Free-for-Life Architecture:
 *
 *  Step 1 — Anti-Fatwa Guardrail:
 *      Blocks explicit Fiqh / Halal-Haram / Divorce / Fatwa queries immediately,
 *      while NEVER blocking valid Dhikr queries like "أذكار الصباح" or "دعاء السفر".
 *
 *  Step 2 — Smart Query Routing (Local Offline RAG vs. Online Dorar.net Verification):
 *      • Local Match (Offline Allowed): Matches audited local Adhkar (`azkar.json` +
 *        `ai_metadata_ar.json`) covering formal, dialectal, and emotional states ("قلقان",
 *        "نوم", "سفر", "أذكار الصباح"). Answers immediately offline with Gemma 3 (270M)
 *        (if downloaded) + verified source badge + direct "افتح الذكر للعد" link.
 *      • External / Deep Query (Internet Required): Rare Dhikr not in local DB or Hadith
 *        authenticity check:
 *          - If OFFLINE: Returns the exact Arabic internet-required notice (zero hallucination).
 *          - If ONLINE: Queries official Dorar.net API directly via OkHttp, filters for
 *            Sahih/Hasan narrations ONLY, formats via Gemma 3 (270M) when installed, and
 *            attaches Verified Source Badge ("المصدر: الدرر السنية - ...").
 */
class NabdAiRouter(
    private val connectivityProbe: ConnectivityProbe,
    private val dorarClient: DorarApiClient = DorarApiClient(),
    private val gemmaEngine: GemmaTextGenerator? = null
) {
    companion object {
        const val LOCAL_MATCH_THRESHOLD: Float = 0.60f

        const val FATWA_REFUSAL_MESSAGE_AR: String =
            "عذراً، «نبض AI» مخصّص فقط للأذكار النبوية الصحيحة، معاني كلماتها، أوقاتها وفضلها من حصن المسلم والدرر السنية. " +
                "لا أقدّم فتاوى فقهية أو أحكام الحلال والحرام والطلاق، ويرجى الرجوع لأهل العلم المختصين في المسائل الفقهية. " +
                "يمكنك سؤالي عن أي ذكر مثل «أذكار الصباح» أو حالتك الشعورية وسأرشدك فوراً."

        const val INTERNET_REQUIRED_MESSAGE_AR: String =
            "هذا السؤال يحتاج للاتصال بالإنترنت للبحث والتحقق من المصادر الموثوقة (الدرر السنية). " +
                "يرجى فتح الإنترنت والمحاولة مرة أخرى، بينما تعمل جميع الأذكار المحفوظة لديك بدون إنترنت."

        private val TASHKEEL_REGEX =
            Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED]")

        // Precision Anti-Fatwa Regex: catches explicit Fiqh/Halal-Haram/Divorce/Fatwa queries ONLY
        // Uses standalone Arabic word boundaries (whitespace/start/end) so "أذكار الصباح" is NEVER blocked.
        private val FATWA_BLOCK_REGEX = Regex(
            pattern = "(^|[\\s؟،.!])(" +
                "فتو[ىي]|استفتاء|افتي|افتيني|" +
                "حلال|حرام|محرم|حرمه|يحرم|" +
                "يجوز|لا\\s+يجوز|هل\\s+يجوز|جايز|" +
                "ما\\s+حكم|حكم\\s+شرعي|حكم\\s+الشرع|الحكم\\s+الشرعي|حكم\\s+الدين|حكم|" +
                "طلاق|الطلاق|طلقت|خلع|عدة\\s+الطلاق|" +
                "ميراث|مواريث|ربا|قرض\\s+ربوي|زكاة\\s+المال|نصاب\\s+الزكاة|" +
                "مذهب|مذاهب|طائفه|بدعه|" +
                "fatwa|halal|haram|divorce|fiqh\\s+ruling" +
                ")([\\s؟،.!]|$)",
            option = RegexOption.IGNORE_CASE
        )

        // Explicit Hadith verification intent -> always routes to Dorar.net online verification
        private val HADITH_VERIFICATION_REGEX = Regex(
            pattern = "(صحه\\s+حديث|صحة\\s+حديث|هل\\s+حديث|هل\\s+هذا\\s+الحديث|تخريج\\s+حديث|درجه\\s+حديث|تحقق\\s+من\\s+حديث|الدرر\\s+السنيه|حديث\\s+صحيح\\s+ام\\s+ضعيف|ما\\s+صحه)",
            option = RegexOption.IGNORE_CASE
        )

        private val ARABIC_STOP_WORDS: Set<String> = setOf(
            "جدا", "عايز", "عاوز", "ابي", "ابغي", "وش", "شو", "بدي", "اقول", "ايه",
            "لما", "عشان", "علشان", "انا", "حاسس", "شاعر", "محتاج", "ممكن",
            "عن", "في", "من", "الي", "علي", "هذا", "هذه", "كيف", "متي", "ما", "هل", "يا",
            "الحديث", "حديث", "صحه", "درجه", "تخريج", "تحقق", "الدرر", "السنيه"
        )

        private val EMOTION_EXPANSIONS: Map<String, List<String>> = mapOf(
            "صباح" to listOf("صباح", "اصبحنا", "فجر", "كرسي"),
            "مساء" to listOf("مساء", "امسينا", "ليل", "مغرب"),
            "قلقان" to listOf("هم", "حزن", "كرب", "ضيق"),
            "قلق" to listOf("هم", "حزن", "كرب", "ضيق"),
            "مخنوق" to listOf("هم", "حزن", "كرب", "ضيق"),
            "متضايق" to listOf("هم", "حزن", "كرب", "ضيق"),
            "حزين" to listOf("هم", "حزن", "كرب", "ربيع"),
            "خايف" to listOf("حفظ", "امان", "روعاتي", "تحصين"),
            "ارق" to listOf("نوم", "فراش", "كوابيس", "ليل"),
            "انام" to listOf("نوم", "فراش", "كوابيس", "ليل"),
            "نوم" to listOf("نوم", "فراش", "مضجع", "جنبي"),
            "كوابيس" to listOf("نوم", "فراش", "معوذات", "حفظ"),
            "صحيت" to listOf("استيقاظ", "احيانا", "نشور"),
            "استيقاظ" to listOf("استيقاظ", "احيانا", "نشور"),
            "متعصب" to listOf("غضب", "شيطان", "سكوت"),
            "غضبان" to listOf("غضب", "شيطان", "سكوت"),
            "غضب" to listOf("غضب", "شيطان", "سكوت"),
            "محتار" to listOf("استخاره", "قرار", "خيره"),
            "استخاره" to listOf("استخاره", "استخيرك", "خيره"),
            "مسافر" to listOf("سفر", "ركوب", "سخر", "طريق"),
            "سفر" to listOf("سفر", "ركوب", "سخر", "وعثاء"),
            "طياره" to listOf("سفر", "ركوب", "سخر"),
            "سياره" to listOf("سفر", "ركوب", "سخر"),
            "مريض" to listOf("مريض", "شفاء", "رقيه", "طهور", "باس"),
            "تعبان" to listOf("مريض", "شفاء", "رقيه", "عافيه"),
            "وجع" to listOf("رقيه", "شفاء", "الم", "احاذر"),
            "حسد" to listOf("رقيه", "عين", "فلق", "حاسد"),
            "عين" to listOf("رقيه", "عين", "فلق", "لامه"),
            "مطر" to listOf("مطر", "صيبا", "سماء"),
            "رعد" to listOf("رعد", "تسبيح", "خيفته"),
            "عاصفه" to listOf("ريح", "رياح", "خيرها"),
            "سوق" to listOf("سوق", "رزق", "دين"),
            "ديون" to listOf("دين", "ضلع", "فقر", "هم"),
            "رزق" to listOf("رزق", "طيب", "سوق", "فضل"),
            "امتحان" to listOf("اشرح", "صدري", "يسر", "علم"),
            "صلاه" to listOf("صلاه", "تسبيح", "سلام", "استغفر"),
            "اذان" to listOf("اذان", "مؤذن", "وسيله", "شفاعه"),
            "مسجد" to listOf("مسجد", "رحمه", "فضل"),
            "بيت" to listOf("منزل", "بيت", "ولجنا", "خروج"),
            "خلاء" to listOf("خلاء", "خبث", "غفرانك"),
            "حمام" to listOf("خلاء", "خبث", "غفرانك"),
            "اكل" to listOf("طعام", "اطعمنا", "افطار"),
            "طعام" to listOf("طعام", "اطعمنا", "افطار"),
            "صائم" to listOf("افطار", "ظما", "عروق", "طعام"),
            "لبس" to listOf("ثوب", "كساني", "جديد"),
            "ثوب" to listOf("ثوب", "كساني", "جديد"),
            "عطس" to listOf("عطاس", "يرحمك", "بالكم"),
            "عطاس" to listOf("عطاس", "يرحمك", "بالكم")
        )
    }

    suspend fun routeQuery(
        rawQuery: String,
        categories: List<AzkarCategory>,
        offlineMetadata: Map<String, OfflineDhikrMetadata>
    ): NabdAiReply = withContext(Dispatchers.Default) {
        val startNanos = System.nanoTime()
        val query = rawQuery.trim()
        val normQuery = normalizeArabic(query)

        // STEP 1: Anti-Fatwa Guardrail (never blocks valid Dhikr queries like "أذكار الصباح")
        if (isFatwaQuery(query)) {
            return@withContext NabdAiReply(
                answerText = FATWA_REFUSAL_MESSAGE_AR,
                sourceBadge = "تنبيه نطاق المساعد (بدون فتاوى)",
                sourceType = NabdAiSourceType.FATWA_BLOCKED,
                latencyMs = elapsedMs(startNanos)
            )
        }

        val explicitHadithVerification = HADITH_VERIFICATION_REGEX.containsMatchIn(normQuery)

        // STEP 2A: Local Offline RAG Search (unless user explicitly requested Dorar Hadith verification)
        val localMatches = findLocalMatches(query, categories, offlineMetadata, limit = 3)
        val topLocal = localMatches.firstOrNull()

        if (!explicitHadithVerification && topLocal != null && topLocal.score >= LOCAL_MATCH_THRESHOLD) {
            return@withContext buildLocalOfflineReply(query, topLocal, startNanos)
        }

        // STEP 2B: External / Deep Query -> Check Internet Connectivity FIRST
        if (!connectivityProbe.isOnline()) {
            return@withContext NabdAiReply(
                answerText = INTERNET_REQUIRED_MESSAGE_AR,
                sourceBadge = "يتطلب الاتصال بالإنترنت للتحقق (الدرر السنية)",
                sourceType = NabdAiSourceType.INTERNET_REQUIRED,
                latencyMs = elapsedMs(startNanos)
            )
        }

        // STEP 2C: Online Free Dorar.net Verification (Sahih / Hasan ONLY)
        val dorarQuery = stripVerificationIntentWords(query).ifBlank { query }
        val dorarResult = dorarClient.searchAuthenticHadith(dorarQuery, maxResults = 2)
        val authenticList = dorarResult.getOrNull().orEmpty()

        if (authenticList.isEmpty()) {
            // If Dorar returned nothing authentic, fall back ONLY if we had a reasonably related local Dhikr
            if (topLocal != null && topLocal.score >= 0.48f) {
                return@withContext buildLocalOfflineReply(query, topLocal, startNanos)
            }
            val message = if (dorarResult.isFailure) {
                "تعذر الوصول إلى موسوعة الدرر السنية حالياً للتحقق من سؤالك. يرجى المحاولة لاحقاً، وتعمل جميع أذكارك المحفوظة محلياً بدون إنترنت."
            } else {
                "لم يُعثر في موسوعة الدرر السنية على حديث صحيح أو حسن يطابق هذا الاستعلام بدقة. نحرص على عدم عرض الروايات الضعيفة أو غير الثابتة؛ جرّب صياغة السؤال بكلمات أوضح من متن الذكر."
            }
            return@withContext NabdAiReply(
                answerText = message,
                sourceBadge = "المصدر: الدرر السنية (تصفية الصحيح والحسن فقط)",
                sourceType = NabdAiSourceType.DORAR_VERIFIED,
                latencyMs = elapsedMs(startNanos)
            )
        }

        val bestHadith = authenticList.first()
        val verifiedContext = buildString {
            append("الحديث الموثّق: ").append(bestHadith.hadithText).append('\n')
            if (bestHadith.narrator.isNotBlank()) append("الراوي: ").append(bestHadith.narrator).append('\n')
            if (bestHadith.scholar.isNotBlank()) append("المحدث: ").append(bestHadith.scholar).append('\n')
            append("المصدر: ").append(bestHadith.sourceBook)
            if (bestHadith.numberOrPage.isNotBlank()) append(" (").append(bestHadith.numberOrPage).append(')')
            append('\n')
            append("خلاصة الحكم: ").append(bestHadith.ruling)
        }

        val gemmaResponse = gemmaEngine?.formulateGroundedAnswer(query, verifiedContext)
        val usedGemma = !gemmaResponse.isNullOrBlank()
        val finalAnswer = gemmaResponse ?: buildString {
            append("تم التحقق عبر موسوعة الدرر السنية (رواية موثّقة):\n\n")
            append("«").append(bestHadith.hadithText).append("»\n\n")
            if (bestHadith.narrator.isNotBlank()) append("• الراوي: ").append(bestHadith.narrator).append('\n')
            append("• المصدر: ").append(bestHadith.sourceBook)
            if (bestHadith.numberOrPage.isNotBlank()) append(" — ").append(bestHadith.numberOrPage)
            append('\n')
            append("• خلاصة حكم المحدث: ").append(bestHadith.ruling)
        }

        val sourceBadge = buildString {
            append("المصدر: الدرر السنية - ").append(bestHadith.sourceBook)
            if (bestHadith.ruling.isNotBlank()) append(" (").append(bestHadith.ruling).append(')')
        }

        return@withContext NabdAiReply(
            answerText = finalAnswer,
            sourceBadge = sourceBadge,
            sourceType = NabdAiSourceType.DORAR_VERIFIED,
            linkedCategoryId = topLocal?.takeIf { it.score >= 0.45f }?.categoryId,
            linkedDhikrId = topLocal?.takeIf { it.score >= 0.45f }?.dhikrId,
            linkedDhikrIndex = topLocal?.takeIf { it.score >= 0.45f }?.indexInCategory ?: 0,
            matchedDhikrText = bestHadith.hadithText,
            usedGemma3 = usedGemma,
            latencyMs = elapsedMs(startNanos)
        )
    }

    /**
     * Precision Anti-Fatwa Check:
     * Blocks Fiqh / Halal-Haram / Divorce / Fatwa queries while allowing Hadith verification
     * ("خلاصة حكم المحدث") and NEVER blocking short Dhikr queries ("أذكار الصباح").
     */
    fun isFatwaQuery(query: String): Boolean {
        val norm = normalizeArabic(query)
        if (norm.isBlank()) return false
        if (HADITH_VERIFICATION_REGEX.containsMatchIn(norm)) return false
        return FATWA_BLOCK_REGEX.containsMatchIn(norm)
    }

    /**
     * Local offline RAG matcher across audited Adhkar categories & compact metadata.
     */
    fun findLocalMatches(
        query: String,
        categories: List<AzkarCategory>,
        offlineMetadata: Map<String, OfflineDhikrMetadata>,
        limit: Int = 3
    ): List<LocalDhikrMatch> {
        if (categories.isEmpty()) return emptyList()

        val normQuery = normalizeArabic(query)
        val rawTokens = normQuery.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (rawTokens.isEmpty()) return emptyList()

        val meaningful = rawTokens.filter { token ->
            token !in ARABIC_STOP_WORDS && stripArabicPrefix(token) !in ARABIC_STOP_WORDS
        }.ifEmpty { rawTokens }

        // Check direct category match first (e.g., "أذكار الصباح", "أذكار النوم", "دعاء السفر")
        val scored = ArrayList<LocalDhikrMatch>()
        for (cat in categories) {
            val normCat = normalizeArabic(cat.nameAr + " " + cat.nameEn)
            val isDirectCategoryMatch = normCat.contains(normQuery) ||
                (meaningful.size <= 3 && meaningful.all { t -> normCat.contains(t) || normCat.contains(stripArabicPrefix(t)) })

            cat.azkarList.forEachIndexed { idx, azkar ->
                val meta = offlineMetadata["${cat.id}_${azkar.id}"]
                val searchable = buildString {
                    append(normCat).append(' ')
                    append(normalizeArabic(azkar.text)).append(' ')
                    append(normalizeArabic(azkar.virtue)).append(' ')
                    if (meta != null) {
                        append(normalizeArabic(meta.detailedExplanation)).append(' ')
                        meta.keywords.forEach { append(normalizeArabic(it)).append(' ') }
                    }
                }

                var score: Float = if (isDirectCategoryMatch) {
                    // Prioritize first prophetic dhikr in exact category match
                    (0.95f - (idx * 0.005f)).coerceAtLeast(0.75f)
                } else {
                    computeTokenScore(meaningful, normCat, searchable)
                }

                if (score >= 0.45f) {
                    scored.add(
                        LocalDhikrMatch(
                            categoryId = cat.id,
                            categoryName = cat.nameAr,
                            dhikrId = azkar.id,
                            indexInCategory = idx,
                            dhikrText = azkar.text,
                            source = azkar.source,
                            hadithNumber = azkar.hadithNumber,
                            shortVirtue = meta?.shortVirtue?.ifBlank { azkar.virtue } ?: azkar.virtue,
                            detailedExplanation = meta?.detailedExplanation.orEmpty(),
                            vocabulary = meta?.vocabulary.orEmpty(),
                            suggestedFollowups = meta?.suggestedFollowups.orEmpty(),
                            botAnswer = meta?.botAnswer?.ifBlank { azkar.virtue } ?: azkar.virtue,
                            repetition = azkar.repetition,
                            score = score
                        )
                    )
                }
            }
        }

        return scored.sortedByDescending { it.score }.take(limit)
    }

    private suspend fun buildLocalOfflineReply(
        query: String,
        match: LocalDhikrMatch,
        startNanos: Long
    ): NabdAiReply {
        val verifiedContext = buildString {
            append("القسم: ").append(match.categoryName).append('\n')
            append("نص الذكر: ").append(match.dhikrText).append('\n')
            append("عدد التكرار: ").append(match.repetition).append('\n')
            append("الفضل: ").append(match.shortVirtue).append('\n')
            if (match.detailedExplanation.isNotBlank()) {
                append("الشرح: ").append(match.detailedExplanation).append('\n')
            }
            append("المصدر: ").append(match.source)
            if (match.hadithNumber.isNotBlank()) append(" (").append(match.hadithNumber).append(')')
        }

        val gemmaResponse = gemmaEngine?.formulateGroundedAnswer(query, verifiedContext)
        val usedGemma = !gemmaResponse.isNullOrBlank()
        val finalAnswer = gemmaResponse ?: buildString {
            append("من ").append(match.categoryName).append(":\n\n")
            append("«").append(match.dhikrText).append("»\n\n")
            if (match.detailedExplanation.isNotBlank()) {
                append(match.detailedExplanation)
            } else {
                append(match.botAnswer)
            }
        }

        val sourceBadge = buildString {
            append("المصدر: ").append(match.source)
            if (match.hadithNumber.isNotBlank()) append(" — رقم ").append(match.hadithNumber)
            append(" (محلي بدون إنترنت)")
        }

        return NabdAiReply(
            answerText = finalAnswer,
            sourceBadge = sourceBadge,
            sourceType = NabdAiSourceType.LOCAL_ADHKAR,
            linkedCategoryId = match.categoryId,
            linkedDhikrId = match.dhikrId,
            linkedDhikrIndex = match.indexInCategory,
            matchedDhikrText = match.dhikrText,
            usedGemma3 = usedGemma,
            latencyMs = elapsedMs(startNanos)
        )
    }

    private fun computeTokenScore(
        meaningfulTokens: List<String>,
        normCat: String,
        searchable: String
    ): Float {
        var tokenScoreSum = 0f
        var categoryHit = false
        for (token in meaningfulTokens) {
            val stem = stripArabicPrefix(token)
            val synonyms = EMOTION_EXPANSIONS[token] ?: EMOTION_EXPANSIONS[stem]

            if (normCat.contains(token) || (stem.length >= 3 && normCat.contains(stem))) {
                categoryHit = true
            }

            val directHit = searchable.contains(token) || (stem.length >= 3 && searchable.contains(stem))
            if (directHit) {
                tokenScoreSum += 1.0f
            } else if (!synonyms.isNullOrEmpty()) {
                val synHits = synonyms.count { syn -> searchable.contains(syn) }
                if (synonyms.any { syn -> normCat.contains(syn) }) categoryHit = true
                if (synHits >= 2) {
                    tokenScoreSum += 0.90f
                } else if (synHits == 1) {
                    tokenScoreSum += 0.72f
                }
            }
        }
        val base = tokenScoreSum / meaningfulTokens.size.toFloat()
        val boost = if (categoryHit && tokenScoreSum > 0f) 0.14f else 0f
        return (base + boost).coerceAtMost(0.98f)
    }

    private fun stripVerificationIntentWords(raw: String): String {
        return raw.replace(
            Regex("(هل\\s+حديث|ما\\s+صحة\\s+حديث|صحة\\s+حديث|تخريج\\s+حديث|درجة\\s+حديث|تحقق\\s+من\\s+حديث|صحيح\\s+ام\\s+ضعيف|صحيح\\s+أم\\s+ضعيف|صحيح؟|في\\s+الدرر\\s+السنية)"),
            " "
        ).replace(Regex("\\s+"), " ").trim()
    }

    private fun elapsedMs(startNanos: Long): Long =
        ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)

    fun normalizeArabic(text: String): String {
        return TASHKEEL_REGEX.replace(text.lowercase(Locale.ROOT), "")
            .replace('إ', 'ا')
            .replace('أ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .trim()
    }

    private fun stripArabicPrefix(token: String): String {
        if (token.length <= 3) return token
        return when {
            token.startsWith("وال") && token.length > 4 -> token.removePrefix("وال")
            token.startsWith("بال") && token.length > 4 -> token.removePrefix("بال")
            token.startsWith("لل") && token.length > 3 -> token.removePrefix("لل")
            token.startsWith("ال") && token.length > 3 -> token.removePrefix("ال")
            token.startsWith("و") && token.length > 4 -> token.removePrefix("و")
            token.startsWith("ب") && token.length > 4 -> token.removePrefix("ب")
            token.startsWith("ف") && token.length > 4 -> token.removePrefix("ف")
            else -> token
        }
    }
}
