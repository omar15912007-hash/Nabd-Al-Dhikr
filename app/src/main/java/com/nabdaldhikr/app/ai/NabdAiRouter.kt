package com.nabdaldhikr.app.ai

import com.nabdaldhikr.app.data.model.Azkar
import com.nabdaldhikr.app.data.model.AzkarCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Smart Hybrid Router for "Nabd AI" (نبض AI) — 100% Free-for-Life Architecture:
 *
 *  1. Anti-Fatwa Guardrail: Blocks explicit Fiqh / Halal-Haram / Divorce / Fatwa queries
 *     immediately, while NEVER blocking greetings or valid Dhikr/Hadith queries.
 *  2. Conversational & Greeting Layer: Replies naturally to Islamic greetings, thanks,
 *     and identity/help questions WITHOUT emitting a random Dhikr card.
 *  3. Context-Aware Follow-Up Layer: Understands repetition count ("أقوله كام مرة؟"),
 *     partial Dhikr completion ("لا اله الا العظيم الحليم ... و كمل"), meaning/virtue/timing,
 *     and next-dhikr requests ("هات غيره") across multi-turn conversations.
 *  4. Empathetic Distress & Nuanced Situation Layer: Synthesizes warm counseling + top
 *     authentic Prophetic sadness/worry adhkar instead of jumping blindly to item #3.
 *  5. Local Offline RAG + Online Dorar.net Verification (Sahih/Hasan ONLY): Queries offline
 *     audited library first; falls back to direct Dorar.net API when online, or returns the
 *     exact Arabic offline notice when offline.
 */
class NabdAiRouter(
    private val connectivityProbe: ConnectivityProbe,
    private val dorarClient: DorarApiClient = DorarApiClient(),
    private val gemmaEngine: GemmaTextGenerator? = null
) {
    private enum class QuestionAspect {
        NONE,
        REPETITION_COUNT,
        MEANING,
        VIRTUE,
        TIMING,
        SOURCE
    }

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

        private val HADITH_VERIFICATION_REGEX = Regex(
            pattern = "(صحه\\s+حديث|صحة\\s+حديث|هل\\s+حديث|هل\\s+هذا\\s+الحديث|تخريج\\s+حديث|درجه\\s+حديث|تحقق\\s+من\\s+حديث|الدرر\\s+السنيه|حديث\\s+صحيح\\s+ام\\s+ضعيف|ما\\s+صحه)",
            option = RegexOption.IGNORE_CASE
        )

        private val ISLAMIC_GREETING_REGEX = Regex(
            pattern = "(?:^|\\s)(?:السلام\\s+عليكم|سلام\\s+عليكم|وعليكم\\s+السلام)(?:\\s+و?\\s*رحمه\\s+الله(?:\\s+و?\\s*بركاته)?)?(?=\\s|$|[،.؟!])"
        )

        private val CASUAL_GREETING_REGEX = Regex(
            pattern = "(?:^|\\s)(?:صباح\\s+الخير|صباح\\s+النور|مساء\\s+الخير|مساء\\s+النور|اهلا(?:\\s+وسهلا)?(?:\\s+بيك|بك)?|مرحبا(?:\\s+بك)?|ازيك|ازي\\s+حضرتك|عامل\\s+ايه|اخبارك(?:\\s+ايه)?|كيف\\s+حالك|سلامو\\s+عليكم|هلو|هاي|hello|hi|salam)(?=\\s|$|[،.؟!])",
            option = RegexOption.IGNORE_CASE
        )

        private val THANKS_REGEX = Regex(
            pattern = "(?:^|\\s)(?:شكرا(?:\\s+جزيلا)?|متشكر|الف\\s+شكر|جزاك\\s+الله(?:\\s+خيرا)?|بارك\\s+الله\\s+فيك|ربنا\\s+يباركلك|ربنا\\s+يكرمك|تسلم(?:\\s+ايدك)?|احسنت)(?=\\s|$|[،.؟!])"
        )

        private val ASSISTANT_IDENTITY_REGEX = Regex(
            pattern = "(انت\\s+مين|مين\\s+انت|عرفني\\s+بنفسك|ما\\s+هو\\s+نبض|بتعمل\\s+ايه|تقدر\\s+تساعدني\\s+في\\s+ايه|وظيفتك\\s+ايه)"
        )

        private val COMPLETION_COMMAND_REGEX = Regex(
            pattern = "(?:^|\\s)(?:كمل|اكمل|كملي|كمله|تكمله|تتمه|باقي\\s+الذكر|باقي\\s+الدعاء|و\\s*كمل|انت\\s+عارفه|كمل\\s+انت)(?=\\s|$|[،.؟!])"
        )

        private val NEXT_DHIKR_REGEX = Regex(
            pattern = "(ذكر\\s+تاني|دعاء\\s+تاني|واحد\\s+تاني|واحد\\s+كمان|كمان\\s+ذكر|كمان\\s+دعاء|هات\\s+غيره|غيره|غير\\s+ده|تاني|اخر|بديل)"
        )

        private val REPETITION_QUESTION_REGEX = Regex(
            pattern = "(كام\\s+مره|كم\\s+مره|عدد\\s+مرات|عدد\\s+التكرار|اقوله\\s+كام|يتقال\\s+كام|نقوله\\s+كام|اكرره\\s+كام|تكراره\\s+كام|how\\s+many\\s+times)"
        )

        private val MEANING_QUESTION_REGEX = Regex(
            pattern = "(معني|معناه|معناها|يعني\\s+ايه|تفسير|المقصود\\s+ب)"
        )

        private val VIRTUE_QUESTION_REGEX = Regex(
            pattern = "(فضل|فضله|فضلها|ثواب|اجر|فايده|فائده|ليه\\s+بنقول|ليه\\s+بيتقال|الحكمه)"
        )

        private val TIMING_QUESTION_REGEX = Regex(
            pattern = "(امتي|متي|وقت|توقيت|تتقال\\s+امتي|اقوله\\s+امتي)"
        )

        private val SOURCE_QUESTION_REGEX = Regex(
            pattern = "(مصدره|مصدر|رواه\\s+مين|حديث\\s+مين|تخريج|في\\s+اي\\s+كتاب)"
        )

        private val SADNESS_DISTRESS_TOKENS: Set<String> = setOf(
            "زعلان", "زعلانه", "زعل", "حزين", "حزينه", "حزن", "مهموم", "هم",
            "مخنوق", "متضايق", "ضايق", "ضيق", "مغموم", "غم", "مكروب", "كرب",
            "موجوع", "مكتئب", "اكتئاب", "محبط", "زهقان", "قلقان", "قلق", "خايف",
            "تعبان نفسيا", "مكسور"
        )

        private val GENERIC_DHIKR_TOKENS: Set<String> = setOf(
            "لا", "اله", "الا", "الله", "رب", "اللهم", "اني", "ان", "في", "من",
            "الي", "علي", "عن", "و", "او", "هو", "هي", "ما", "انت", "بك", "لك",
            "ذكر", "اذكار", "دعاء", "ادعيه", "حديث", "قول", "قال", "فيه", "فيها"
        )

        private val ARABIC_STOP_WORDS: Set<String> = setOf(
            "جدا", "عايز", "عاوز", "ابي", "ابغي", "وش", "شو", "بدي", "اقول", "اقوله", "ايه",
            "لما", "عشان", "علشان", "انا", "حاسس", "شاعر", "محتاج", "ممكن", "طيب",
            "مثلا", "حاجه", "معينه", "بسبب", "حصل", "حصلت", "حصلتلي", "عندي", "لو", "حد",
            "اكون", "يكون", "تكون", "ذكر", "دعاء", "اذكار", "ادعيه", "مره", "مرات", "كام", "كم",
            "عن", "في", "من", "الي", "علي", "هذا", "هذه", "كيف", "متي", "ما", "هل", "يا", "بس",
            "الحديث", "حديث", "صحه", "درجه", "تخريج", "تحقق", "الدرر", "السنيه", "كمل", "انت", "عارفه"
        )

        private val EMOTION_EXPANSIONS: Map<String, List<String>> = mapOf(
            "صباح" to listOf("صباح", "اصبحنا", "فجر", "كرسي"),
            "مساء" to listOf("مساء", "امسينا", "ليل", "مغرب"),
            "زعلان" to listOf("هم", "حزن", "كرب", "عظيم", "حليم"),
            "زعلانه" to listOf("هم", "حزن", "كرب", "عظيم", "حليم"),
            "زعل" to listOf("هم", "حزن", "كرب", "عظيم", "حليم"),
            "قلقان" to listOf("هم", "حزن", "كرب", "ضيق"),
            "قلق" to listOf("هم", "حزن", "كرب", "ضيق"),
            "مخنوق" to listOf("هم", "حزن", "كرب", "ضيق", "عظيم"),
            "متضايق" to listOf("هم", "حزن", "كرب", "ضيق"),
            "ضايق" to listOf("هم", "حزن", "كرب", "ضيق"),
            "مهموم" to listOf("هم", "حزن", "كرب", "ربيع"),
            "مغموم" to listOf("هم", "حزن", "كرب", "عظيم"),
            "مكروب" to listOf("كرب", "حليم", "عرش", "هم"),
            "حزين" to listOf("هم", "حزن", "كرب", "ربيع", "حليم"),
            "حزن" to listOf("هم", "حزن", "كرب", "ربيع", "حليم"),
            "مكتئب" to listOf("هم", "حزن", "كرب", "ربيع"),
            "زهقان" to listOf("هم", "حزن", "ضيق"),
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
        offlineMetadata: Map<String, OfflineDhikrMetadata>,
        context: AiConversationContext = AiConversationContext()
    ): NabdAiReply = withContext(Dispatchers.Default) {
        val startNanos = System.nanoTime()
        val query = rawQuery.trim()
        val normQuery = normalizeArabic(query)

        // LAYER 1: Anti-Fatwa Guardrail (never blocks greetings or valid Dhikr queries)
        if (isFatwaQuery(query)) {
            return@withContext NabdAiReply(
                answerText = FATWA_REFUSAL_MESSAGE_AR,
                sourceBadge = "تنبيه نطاق المساعد (بدون فتاوى)",
                sourceType = NabdAiSourceType.FATWA_BLOCKED,
                latencyMs = elapsedMs(startNanos)
            )
        }

        // LAYER 2: Greetings, Social Turns, and Mixed Greeting+Question extraction
        val socialResolution = resolveSocialOrExtractRemainder(query, normQuery, startNanos)
        if (socialResolution.pureSocialReply != null) {
            return@withContext socialResolution.pureSocialReply
        }
        val effectiveQuery = socialResolution.remainingQuery
        val effectiveNorm = normalizeArabic(effectiveQuery)
        val prefixGreeting = socialResolution.greetingPrefix

        // LAYER 3: "Another / Next Dhikr" Follow-up on active category context
        if (NEXT_DHIKR_REGEX.containsMatchIn(effectiveNorm) && !context.lastCategoryId.isNullOrBlank()) {
            val nextReply = buildNextDhikrReply(effectiveQuery, categories, offlineMetadata, context, startNanos)
            if (nextReply != null) return@withContext withGreetingPrefix(nextReply, prefixGreeting)
        }

        // LAYER 4: Follow-Up Aspect Questions ("أقوله كام مرة؟", "إيه معناه؟", "إيه فضله؟")
        val aspect = detectQuestionAspect(effectiveNorm)
        val wantsCompletion = COMPLETION_COMMAND_REGEX.containsMatchIn(effectiveNorm)
        val explicitHadithVerification = HADITH_VERIFICATION_REGEX.containsMatchIn(effectiveNorm)

        // Check partial Dhikr completion BEFORE topic matching (e.g. "لا اله الا العظيم الحليم لا اله الا الله و كمل")
        val completionMatch = findPartialDhikrCompletionMatch(effectiveQuery, categories, offlineMetadata)
        if (completionMatch != null && (!explicitHadithVerification || aspect == QuestionAspect.REPETITION_COUNT)) {
            return@withContext withGreetingPrefix(
                buildCompletionOrAspectReply(effectiveQuery, completionMatch, aspect, wantsCompletion, context, startNanos),
                prefixGreeting
            )
        }

        // If user asked a follow-up aspect question (e.g., "أقوله كام مرة؟") without naming a new topic, use active conversation context
        if (aspect != QuestionAspect.NONE && !hasStandaloneTopic(effectiveNorm) && context.lastDhikrText != null) {
            val ctxReply = buildContextFollowUpReply(effectiveQuery, aspect, context, startNanos)
            return@withContext withGreetingPrefix(ctxReply, prefixGreeting)
        }

        // LAYER 5: Empathetic Sadness / Worry / Nuanced Personal Situation Counseling
        if (!explicitHadithVerification &&
            (aspect == QuestionAspect.NONE || aspect == QuestionAspect.REPETITION_COUNT) &&
            isSadnessDistressQuery(effectiveNorm)
        ) {
            return@withContext withGreetingPrefix(
                buildEmotionalCounselingReply(effectiveQuery, categories, offlineMetadata, context, startNanos),
                prefixGreeting
            )
        }

        // LAYER 6: Local Offline RAG Search (strip aspect/completion noise so topic score stays high)
        val ragSearchQuery = stripAspectAndCompletionWords(effectiveNorm).ifBlank { effectiveQuery }
        val localMatches = findLocalMatches(ragSearchQuery, categories, offlineMetadata, limit = 3)
        val topLocal = localMatches.firstOrNull()

        if (!explicitHadithVerification && topLocal != null && topLocal.score >= LOCAL_MATCH_THRESHOLD) {
            val reply = if (aspect != QuestionAspect.NONE || wantsCompletion) {
                buildCompletionOrAspectReply(effectiveQuery, topLocal, aspect, wantsCompletion, context, startNanos)
            } else {
                buildLocalOfflineReply(effectiveQuery, topLocal, context, startNanos)
            }
            return@withContext withGreetingPrefix(reply, prefixGreeting)
        }

        // LAYER 7: External / Deep / Rare Query -> Check Internet Connectivity FIRST
        if (!connectivityProbe.isOnline()) {
            return@withContext NabdAiReply(
                answerText = INTERNET_REQUIRED_MESSAGE_AR,
                sourceBadge = "يتطلب الاتصال بالإنترنت للتحقق (الدرر السنية)",
                sourceType = NabdAiSourceType.INTERNET_REQUIRED,
                latencyMs = elapsedMs(startNanos)
            )
        }

        // LAYER 8: Online Free Dorar.net Verification (Sahih / Hasan ONLY)
        val dorarQuery = extractDorarSearchKeywords(effectiveQuery)
        val dorarResult = dorarClient.searchAuthenticHadith(dorarQuery, maxResults = 2)
        val authenticList = dorarResult.getOrNull().orEmpty()

        if (authenticList.isEmpty()) {
            if (topLocal != null && topLocal.score >= 0.48f) {
                val fallback = buildLocalOfflineReply(effectiveQuery, topLocal, context, startNanos)
                return@withContext withGreetingPrefix(fallback, prefixGreeting)
            }
            val message = if (dorarResult.isFailure) {
                "تعذر الوصول إلى موسوعة الدرر السنية حالياً للتحقق من سؤالك. يرجى المحاولة لاحقاً، وتعمل جميع أذكارك المحفوظة محلياً بدون إنترنت."
            } else {
                "لم يُعثر في موسوعة الدرر السنية على حديث صحيح أو حسن يطابق هذا الاستعلام بدقة. نحرص على عدم عرض الروايات الضعيفة أو غير الثابتة؛ يمكنك إعادة صياغة السؤال بكلمات أوضح من الذكر أو الحديث."
            }
            return@withContext withGreetingPrefix(
                NabdAiReply(
                    answerText = message,
                    sourceBadge = "المصدر: الدرر السنية (تصفية الصحيح والحسن فقط)",
                    sourceType = NabdAiSourceType.DORAR_VERIFIED,
                    latencyMs = elapsedMs(startNanos)
                ),
                prefixGreeting
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

        val gemmaIntro = gemmaEngine?.formulateGroundedAnswer(effectiveQuery, verifiedContext, context.recentTurns)
        val usedGemma = !gemmaIntro.isNullOrBlank()
        val finalAnswer = buildString {
            if (usedGemma) {
                append(gemmaIntro).append("\n\n")
            } else {
                append("تم التحقق عبر موسوعة الدرر السنية (رواية موثّقة):\n\n")
            }
            append("«").append(bestHadith.hadithText).append("»\n\n")
            if (bestHadith.narrator.isNotBlank()) append("• الراوي: ").append(bestHadith.narrator).append('\n')
            append("• المصدر: ").append(bestHadith.sourceBook)
            if (bestHadith.numberOrPage.isNotBlank()) append(" — ").append(bestHadith.numberOrPage)
            append('\n')
            append("• خلاصة حكم المحدث: ").append(bestHadith.ruling)
            if (aspect == QuestionAspect.REPETITION_COUNT) {
                append("\n• عدد التكرار: إن لم يرد في متن الرواية عددٌ مخصوص، فهو ذكر ودعاء مطلق يُقال مرةً بحضور قلب ويُستحب الإكثار منه دون تقييد.")
            }
        }

        val sourceBadge = buildString {
            append("المصدر: الدرر السنية - ").append(bestHadith.sourceBook)
            if (bestHadith.ruling.isNotBlank()) append(" (").append(bestHadith.ruling).append(')')
        }

        val dorarReply = NabdAiReply(
            answerText = finalAnswer,
            sourceBadge = sourceBadge,
            sourceType = NabdAiSourceType.DORAR_VERIFIED,
            linkedCategoryId = topLocal?.takeIf { it.score >= 0.48f }?.categoryId,
            linkedDhikrId = topLocal?.takeIf { it.score >= 0.48f }?.dhikrId,
            linkedDhikrIndex = topLocal?.takeIf { it.score >= 0.48f }?.indexInCategory ?: 0,
            matchedDhikrText = bestHadith.hadithText,
            matchedSource = bestHadith.sourceBook,
            matchedHadithNumber = bestHadith.numberOrPage,
            usedGemma3 = usedGemma,
            latencyMs = elapsedMs(startNanos)
        )
        return@withContext withGreetingPrefix(dorarReply, prefixGreeting)
    }

    fun isFatwaQuery(query: String): Boolean {
        val norm = normalizeArabic(query)
        if (norm.isBlank()) return false
        if (HADITH_VERIFICATION_REGEX.containsMatchIn(norm)) return false
        return FATWA_BLOCK_REGEX.containsMatchIn(norm)
    }

    /**
     * Order-preserving & distinctive-token completion matcher:
     * Handles partial or slightly omitted quotes such as
     * "لا اله الا العظيم الحليم لا اله الا الله و كمل".
     */
    fun findPartialDhikrCompletionMatch(
        query: String,
        categories: List<AzkarCategory>,
        offlineMetadata: Map<String, OfflineDhikrMetadata>
    ): LocalDhikrMatch? {
        if (categories.isEmpty()) return null
        val cleaned = COMPLETION_COMMAND_REGEX.replace(normalizeArabic(query), " ")
            .replace(Regex("[؟،.!]+"), " ")
            .trim()
        val rawTokens = cleaned.split(Regex("\\s+")).filter { it.length >= 2 }
        if (rawTokens.isEmpty()) return null

        val wantsCompletion = COMPLETION_COMMAND_REGEX.containsMatchIn(normalizeArabic(query))
        val distinctiveTokens = rawTokens.filter { tok ->
            tok.length >= 3 &&
                tok !in GENERIC_DHIKR_TOKENS &&
                tok !in ARABIC_STOP_WORDS
        }.distinct()

        // Require at least 2 distinctive words (e.g. "العظيم" + "الحليم"), or 1 distinctive word + explicit completion request
        if (distinctiveTokens.isEmpty() || (distinctiveTokens.size < 2 && !(wantsCompletion && rawTokens.size >= 3))) {
            return null
        }

        val queryNorm = normalizeArabic(query)
        val prefersWorry = queryNorm.contains("حزن") || queryNorm.contains("زعل") || queryNorm.contains("هم")

        var bestMatch: LocalDhikrMatch? = null
        for (cat in categories) {
            cat.azkarList.forEachIndexed { idx, azkar ->
                val textNorm = normalizeArabic(azkar.text)
                val textTokens = textNorm.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

                val matchedDistinctive = distinctiveTokens.count { tok ->
                    textTokens.contains(tok) || textNorm.contains(tok)
                }
                if (matchedDistinctive == 0) return@forEachIndexed

                val distinctiveRecall = matchedDistinctive.toFloat() / distinctiveTokens.size.toFloat()
                if (distinctiveRecall < 0.65f) return@forEachIndexed

                val orderedHits = countOrderedSubsequence(distinctiveTokens, textTokens)
                val orderRatio = orderedHits.toFloat() / distinctiveTokens.size.toFloat()
                val allRecall = rawTokens.count { tok -> textTokens.contains(tok) }.toFloat() / rawTokens.size.toFloat()

                val categoryPreferenceBoost = when {
                    prefersWorry && cat.id == "worry_azkar" -> 0.04f
                    !prefersWorry && cat.id == "distress_azkar" -> 0.01f
                    else -> 0f
                }

                val totalScore = (0.60f * distinctiveRecall + 0.25f * orderRatio + 0.15f * allRecall + categoryPreferenceBoost)
                    .coerceAtMost(0.99f)

                val currentBest = bestMatch
                if (totalScore >= 0.72f && (currentBest == null || totalScore > currentBest.score)) {
                    bestMatch = toLocalMatch(cat, idx, azkar, offlineMetadata["${cat.id}_${azkar.id}"], totalScore)
                }
            }
        }
        return bestMatch
    }

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

        val scored = ArrayList<LocalDhikrMatch>()
        for (cat in categories) {
            val normCat = normalizeArabic(cat.nameAr + " " + cat.nameEn)
            val isDirectCategoryMatch = (meaningful.size in 1..3) && (
                normCat.contains(normQuery) ||
                    meaningful.all { t -> normCat.contains(t) || normCat.contains(stripArabicPrefix(t)) }
                )

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

                val score: Float = if (isDirectCategoryMatch) {
                    (0.95f - (idx * 0.005f)).coerceAtLeast(0.75f)
                } else {
                    computeTokenScore(meaningful, normCat, searchable)
                }

                if (score >= 0.45f) {
                    scored.add(toLocalMatch(cat, idx, azkar, meta, score))
                }
            }
        }

        return scored.sortedByDescending { it.score }.take(limit)
    }

    private data class SocialResolution(
        val pureSocialReply: NabdAiReply? = null,
        val remainingQuery: String,
        val greetingPrefix: String? = null
    )

    private fun resolveSocialOrExtractRemainder(
        rawQuery: String,
        normQuery: String,
        startNanos: Long
    ): SocialResolution {
        if (normQuery.isBlank()) {
            return SocialResolution(
                pureSocialReply = conversationalReply(
                    "أهلاً بك في «نبض AI» 🌿 اكتب لي سؤالك أو الحالة التي تمر بها وسأرشدك للذكر الصحيح بإذن الله.",
                    startNanos
                ),
                remainingQuery = rawQuery
            )
        }

        val hasIslamicGreeting = ISLAMIC_GREETING_REGEX.containsMatchIn(normQuery)
        val hasCasualGreeting = CASUAL_GREETING_REGEX.containsMatchIn(normQuery)

        var remainder = normQuery
        if (hasIslamicGreeting) remainder = ISLAMIC_GREETING_REGEX.replace(remainder, " ")
        if (hasCasualGreeting) remainder = CASUAL_GREETING_REGEX.replace(remainder, " ")
        remainder = remainder
            .replace(Regex("(^|\\s)و(?=\\s|$)"), " ")
            .replace(Regex("[،.؟!]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if ((hasIslamicGreeting || hasCasualGreeting) && remainder.isEmpty()) {
            val replyText = if (hasIslamicGreeting) {
                "وعليكم السلام ورحمة الله وبركاته 🌿\nأهلاً بك يا طيب في «نبض AI». كيف حالك اليوم؟ يمكنك أن تحكي لي ما تشعر به أو تسألني عن أي ذكر، تكملة دعاء، عدد تكراره، أو التحقق من صحة حديث وسأجيبك بدقة من السنة الصحيحة."
            } else {
                "أهلاً ومرحباً بك 🌿 أنا هنا لمساعدتك في الأذكار الصحيحة والأدعية المأثورة وشرح معانيها وعددها أو التحقق من صحة الأحاديث. بماذا تحب أن نبدأ؟"
            }
            return SocialResolution(
                pureSocialReply = conversationalReply(replyText, startNanos),
                remainingQuery = ""
            )
        }

        if (THANKS_REGEX.containsMatchIn(normQuery)) {
            val afterThanks = THANKS_REGEX.replace(normQuery, " ")
                .replace(Regex("[،.؟!]+"), " ")
                .trim()
            if (afterThanks.length <= 4) {
                return SocialResolution(
                    pureSocialReply = conversationalReply(
                        "وإياكم يا رب، بارك الله فيك ونفع بك 🌿 يسعدني دائماً خدمتك في كل وقت.",
                        startNanos
                    ),
                    remainingQuery = ""
                )
            }
        }

        if (ASSISTANT_IDENTITY_REGEX.containsMatchIn(normQuery) && normQuery.length <= 45) {
            return SocialResolution(
                pureSocialReply = conversationalReply(
                    "أنا «نبض AI»، رفيقك للأذكار الصحيحة من حصن المسلم والصحيحين 🌿\n" +
                        "أساعدك بدون إنترنت في:\n" +
                        "• إرشادك للأذكار المناسبة لحالتك ومواقف يومك.\n" +
                        "• تكملة أي ذكر تبدأ به مع مصدره وعدد مراته.\n" +
                        "• توضيح الفضل والمعاني، والتحقق المباشر من الأحاديث عبر الدرر السنية عند الاتصال بالإنترنت.",
                    startNanos
                ),
                remainingQuery = ""
            )
        }

        val prefix = when {
            hasIslamicGreeting -> "وعليكم السلام ورحمة الله وبركاته 🌿\n\n"
            hasCasualGreeting -> "أهلاً بك 🌿\n\n"
            else -> null
        }
        val queryToRoute = if (prefix != null && remainder.isNotBlank()) remainder else rawQuery
        return SocialResolution(pureSocialReply = null, remainingQuery = queryToRoute, greetingPrefix = prefix)
    }

    private fun conversationalReply(text: String, startNanos: Long): NabdAiReply = NabdAiReply(
        answerText = text,
        sourceBadge = "محادثة ودّية • نبض AI",
        sourceType = NabdAiSourceType.CONVERSATIONAL,
        linkedCategoryId = null,
        latencyMs = elapsedMs(startNanos)
    )

    private fun withGreetingPrefix(reply: NabdAiReply, prefix: String?): NabdAiReply {
        if (prefix.isNullOrBlank()) return reply
        return reply.copy(answerText = prefix + reply.answerText)
    }

    private fun isSadnessDistressQuery(normQuery: String): Boolean {
        if (SADNESS_DISTRESS_TOKENS.any { token -> normQuery.contains(token) }) return true
        val tokens = normQuery.split(Regex("\\s+"))
        return tokens.any { tok ->
            tok == "زعل" || tok == "زعلان" || tok == "حزن" || tok == "هم" || tok == "كرب" ||
                stripArabicPrefix(tok) in SADNESS_DISTRESS_TOKENS
        }
    }

    private suspend fun buildEmotionalCounselingReply(
        query: String,
        categories: List<AzkarCategory>,
        offlineMetadata: Map<String, OfflineDhikrMetadata>,
        context: AiConversationContext,
        startNanos: Long
    ): NabdAiReply {
        val worryCat = categories.firstOrNull { it.id == "worry_azkar" }
        val distressCat = categories.firstOrNull { it.id == "distress_azkar" }

        val azkarHalim = worryCat?.azkarList?.firstOrNull { it.id == 1706 }
            ?: distressCat?.azkarList?.firstOrNull { it.id == 1801 }
        val azkarAbduka = worryCat?.azkarList?.firstOrNull { it.id == 1701 }
        val azkarAoodhu = worryCat?.azkarList?.firstOrNull { it.id == 1702 }
        val azkarYunus = worryCat?.azkarList?.firstOrNull { it.id == 1703 }
            ?: distressCat?.azkarList?.firstOrNull { it.id == 1803 }

        val primaryMatchAzkar = azkarHalim ?: azkarAbduka ?: worryCat?.azkarList?.firstOrNull()
        val primaryIndex = if (primaryMatchAzkar != null && worryCat != null) {
            worryCat.azkarList.indexOfFirst { it.id == primaryMatchAzkar.id }.coerceAtLeast(0)
        } else {
            0
        }

        val normQ = normalizeArabic(query)
        val hasSpecificIssue = normQ.contains("حاجه معينه") || normQ.contains("مشكله") ||
            normQ.contains("سبب") || normQ.contains("بسبب") || normQ.split(" ").size >= 5

        val empathyOpening = if (hasSpecificIssue) {
            "هوّن على قلبك، وأسأل الله العظيم أن يفرّج همّك في هذا الأمر تحديداً، ويجبر خاطرك ويكتب لك الخير والسكينة 🌿"
        } else {
            "سلامة قلبك من كل ضيق وحزن 🌿 أبشر؛ فقد علّمنا النبي ﷺ عند الحزن والهم والكرب كلمات عظيمة يبدّل الله بها الهم فرجاً:"
        }

        val verifiedContextForGemma = buildString {
            append("حالة المستخدم: ").append(query).append('\n')
            if (azkarHalim != null) {
                append("1) دعاء الكرب والحزن (البخاري 6346 ومسلم): ").append(azkarHalim.text).append('\n')
            }
            if (azkarAbduka != null) {
                append("2) دعاء ذهاب الهم (أحمد 3712): ").append(azkarAbduka.text).append('\n')
            }
        }
        val gemmaIntro = gemmaEngine?.formulateGroundedAnswer(query, verifiedContextForGemma, context.recentTurns)
        val usedGemma = !gemmaIntro.isNullOrBlank()

        val answer = buildString {
            append(gemmaIntro ?: empathyOpening).append("\n\n")
            if (azkarHalim != null) {
                append("١. **دعاء تفريج الكرب والحزن الشديد** *(متفق عليه: البخاري 6346 ومسلم)*:\n")
                append("«").append(azkarHalim.text).append("»\n")
                append("• **العدد:** يُقال مرة واحدة بيقين، ويُستحب تكراره (3 مرات فأكثر) حتى ينشرح صدرك.\n\n")
            }
            if (azkarAbduka != null) {
                append("٢. **دعاء ذهاب الهم وإبداله فرحاً** *(أحمد 3712 — صحيح)*:\n")
                append("«").append(azkarAbduka.text).append("»\n")
                append("• **العدد:** مرة واحدة بتدبّر.\n\n")
            }
            if (azkarAoodhu != null) {
                append("٣. **الاستعاذة النبوية الجامعة** *(صحيح البخاري 6369)*:\n")
                append("«").append(azkarAoodhu.text).append("»\n")
            } else if (azkarYunus != null) {
                append("٣. **دعوة ذي النون** *(الترمذي 3505)*:\n")
                append("«").append(azkarYunus.text).append("»\n")
            }
            append("\nيمكنك سؤالي عن أي واحد منها (معناه أو كم مرة يُقال) أو الضغط بالأسفل لفتح القسم.")
        }

        val primaryMeta = primaryMatchAzkar?.let { offlineMetadata["worry_azkar_${it.id}"] }
        return NabdAiReply(
            answerText = answer.trim(),
            sourceBadge = "المصدر: صحيح البخاري ومسلم ومسند أحمد (أذكار الهم والحزن — محلي)",
            sourceType = NabdAiSourceType.LOCAL_ADHKAR,
            linkedCategoryId = worryCat?.id,
            linkedDhikrId = primaryMatchAzkar?.id,
            linkedDhikrIndex = 0, // Always open Worry Azkar cleanly from top (#1) rather than jumping randomly to #3
            matchedDhikrText = primaryMatchAzkar?.text,
            matchedSource = primaryMatchAzkar?.source,
            matchedHadithNumber = primaryMatchAzkar?.hadithNumber,
            matchedRepetition = primaryMatchAzkar?.repetition ?: 1,
            matchedShortVirtue = primaryMeta?.shortVirtue ?: primaryMatchAzkar?.virtue,
            matchedDetailedExplanation = primaryMeta?.detailedExplanation,
            matchedVocabulary = primaryMeta?.vocabulary.orEmpty(),
            usedGemma3 = usedGemma,
            latencyMs = elapsedMs(startNanos)
        )
    }

    private suspend fun buildCompletionOrAspectReply(
        query: String,
        match: LocalDhikrMatch,
        aspect: QuestionAspect,
        wantsCompletion: Boolean,
        context: AiConversationContext,
        startNanos: Long
    ): NabdAiReply {
        val countLabel = formatRepetitionExplanation(match.repetition, match.categoryId)
        val sourceLine = if (match.hadithNumber.isNotBlank()) {
            "${match.source} (رقم ${match.hadithNumber})"
        } else {
            match.source
        }

        val verifiedContext = buildString {
            append("القسم: ").append(match.categoryName).append('\n')
            append("النص الكامل للذكر: ").append(match.dhikrText).append('\n')
            append("التكرار: ").append(countLabel).append('\n')
            append("الفضل: ").append(match.shortVirtue).append('\n')
            if (match.detailedExplanation.isNotBlank()) append("الشرح: ").append(match.detailedExplanation).append('\n')
            append("المصدر: ").append(sourceLine)
        }
        val gemmaIntro = gemmaEngine?.formulateGroundedAnswer(query, verifiedContext, context.recentTurns)
        val usedGemma = !gemmaIntro.isNullOrBlank()

        val answer = buildString {
            when {
                aspect == QuestionAspect.REPETITION_COUNT -> {
                    append("**كم مرة يُقال هذا الذكر؟**\n")
                    append("• ").append(countLabel).append("\n\n")
                    append("«").append(match.dhikrText).append("»\n\n")
                    append("• **المصدر:** ").append(sourceLine).append('\n')
                    if (match.shortVirtue.isNotBlank()) append("• **الفضل:** ").append(match.shortVirtue)
                }
                aspect == QuestionAspect.MEANING -> {
                    if (usedGemma) append(gemmaIntro).append("\n\n")
                    if (match.vocabulary.isNotEmpty()) {
                        append("**معاني كلمات الذكر:**\n")
                        match.vocabulary.forEach { v ->
                            append("• **").append(v.word).append(":** ").append(v.meaning).append('\n')
                        }
                        append('\n')
                    }
                    if (match.detailedExplanation.isNotBlank()) append(match.detailedExplanation).append("\n\n")
                    append("«").append(match.dhikrText).append("»\n")
                    append("• **المصدر:** ").append(sourceLine)
                }
                aspect == QuestionAspect.VIRTUE -> {
                    append("**فضل وأجر هذا الذكر:**\n")
                    append(match.shortVirtue.ifBlank { match.detailedExplanation }).append("\n\n")
                    append("«").append(match.dhikrText).append("»\n")
                    append("• **العدد:** ").append(countLabel).append(" — **المصدر:** ").append(sourceLine)
                }
                aspect == QuestionAspect.TIMING -> {
                    append("**وقت وكيفية قول هذا الذكر (${match.categoryName}):**\n")
                    append(match.detailedExplanation.ifBlank { "يُقال في موضع ${match.categoryName} بحضور قلب." }).append("\n\n")
                    append("«").append(match.dhikrText).append("»\n")
                    append("• **العدد:** ").append(countLabel)
                }
                aspect == QuestionAspect.SOURCE -> {
                    append("**المصدر الموثّق للذكر:**\n")
                    append("ثبت في **").append(sourceLine).append("** ضمن **").append(match.categoryName).append("**.\n\n")
                    append("«").append(match.dhikrText).append("»")
                }
                else -> {
                    // Dhikr completion (wantsCompletion or partial match)
                    if (usedGemma) {
                        append(gemmaIntro).append("\n\n")
                    } else if (wantsCompletion) {
                        append("إليك **النص الكامل الصحيح للذكر** من ").append(match.categoryName).append(":\n\n")
                    } else {
                        append("من **").append(match.categoryName).append("**:\n\n")
                    }
                    append("«").append(match.dhikrText).append("»\n\n")
                    append("• **كم مرة يُقال:** ").append(countLabel).append('\n')
                    if (match.shortVirtue.isNotBlank()) append("• **الفضل:** ").append(match.shortVirtue).append('\n')
                    append("• **المصدر:** ").append(sourceLine)
                }
            }
        }

        return NabdAiReply(
            answerText = answer.trim(),
            sourceBadge = "المصدر: $sourceLine (محلي بدون إنترنت)",
            sourceType = NabdAiSourceType.LOCAL_ADHKAR,
            linkedCategoryId = match.categoryId,
            linkedDhikrId = match.dhikrId,
            linkedDhikrIndex = match.indexInCategory,
            matchedDhikrText = match.dhikrText,
            matchedSource = match.source,
            matchedHadithNumber = match.hadithNumber,
            matchedRepetition = match.repetition,
            matchedShortVirtue = match.shortVirtue,
            matchedDetailedExplanation = match.detailedExplanation,
            matchedVocabulary = match.vocabulary,
            usedGemma3 = usedGemma,
            latencyMs = elapsedMs(startNanos)
        )
    }

    private suspend fun buildContextFollowUpReply(
        query: String,
        aspect: QuestionAspect,
        context: AiConversationContext,
        startNanos: Long
    ): NabdAiReply {
        val catId = context.lastCategoryId ?: ""
        val catName = context.lastCategoryName ?: "الذكر السابق"
        val dhikrText = context.lastDhikrText.orEmpty()
        val source = context.lastSource?.ifBlank { "حصن المسلم" } ?: "حصن المسلم"
        val hadithNum = context.lastHadithNumber.orEmpty()
        val rep = context.lastRepetition ?: 1
        val countLabel = formatRepetitionExplanation(rep, catId)
        val sourceLine = if (hadithNum.isNotBlank()) "$source (رقم $hadithNum)" else source

        val answer = when (aspect) {
            QuestionAspect.REPETITION_COUNT -> buildString {
                append("بخصوص الذكر الذي نتحدث عنه في **$catName**:\n\n")
                append("«$dhikrText»\n\n")
                append("• **كم مرة يُقال:** $countLabel.\n")
                append("• **المصدر:** $sourceLine.")
            }
            QuestionAspect.MEANING -> buildString {
                if (context.lastVocabulary.isNotEmpty()) {
                    append("**معاني كلمات الذكر:**\n")
                    context.lastVocabulary.forEach { append("• **${it.word}:** ${it.meaning}\n") }
                    append('\n')
                }
                val expl = context.lastDetailedExplanation.orEmpty()
                if (expl.isNotBlank()) {
                    append(expl)
                } else {
                    val gemma = gemmaEngine?.formulateGroundedAnswer(
                        query,
                        "الذكر: $dhikrText\nالفضل: ${context.lastShortVirtue.orEmpty()}\nالمصدر: $sourceLine",
                        context.recentTurns
                    )
                    append(gemma ?: "يدل هذا الذكر المبارك على توحيد الله والالتجاء إليه بصدق وطمأنينة ($sourceLine).")
                }
            }
            QuestionAspect.VIRTUE -> buildString {
                val v = context.lastShortVirtue?.ifBlank { context.lastDetailedExplanation.orEmpty() }.orEmpty()
                append("**فضل هذا الذكر:**\n")
                append(v.ifBlank { "ذكر عظيم ثابت في السنة النبوية يورث السكينة وتفريج الهم." })
                append("\n\n• **المصدر:** $sourceLine")
            }
            QuestionAspect.TIMING -> buildString {
                val expl = context.lastDetailedExplanation.orEmpty()
                append("**وقت وهدي هذا الذكر:**\n")
                append(expl.ifBlank { "يُقال في موضع «$catName» بتدبر وخشوع ($countLabel)." })
            }
            QuestionAspect.SOURCE -> {
                "المصدر الموثّق لهذا الذكر هو: **$sourceLine** (ضمن قسم: **$catName**)."
            }
            QuestionAspect.NONE -> dhikrText
        }

        return NabdAiReply(
            answerText = answer.trim(),
            sourceBadge = "المصدر: $sourceLine (سياق المحادثة • محلي)",
            sourceType = NabdAiSourceType.LOCAL_ADHKAR,
            linkedCategoryId = context.lastCategoryId,
            linkedDhikrId = context.lastDhikrId,
            linkedDhikrIndex = context.lastDhikrIndex,
            matchedDhikrText = dhikrText,
            matchedSource = source,
            matchedHadithNumber = hadithNum,
            matchedRepetition = rep,
            matchedShortVirtue = context.lastShortVirtue,
            matchedDetailedExplanation = context.lastDetailedExplanation,
            matchedVocabulary = context.lastVocabulary,
            latencyMs = elapsedMs(startNanos)
        )
    }

    private suspend fun buildNextDhikrReply(
        query: String,
        categories: List<AzkarCategory>,
        offlineMetadata: Map<String, OfflineDhikrMetadata>,
        context: AiConversationContext,
        startNanos: Long
    ): NabdAiReply? {
        val cat = categories.firstOrNull { it.id == context.lastCategoryId } ?: return null
        if (cat.azkarList.size <= 1) return null
        val nextIdx = (context.lastDhikrIndex + 1) % cat.azkarList.size
        val nextAzkar = cat.azkarList[nextIdx]
        val meta = offlineMetadata["${cat.id}_${nextAzkar.id}"]
        val match = toLocalMatch(cat, nextIdx, nextAzkar, meta, score = 0.95f)
        return buildLocalOfflineReply(query, match, context, startNanos)
    }

    private suspend fun buildLocalOfflineReply(
        query: String,
        match: LocalDhikrMatch,
        context: AiConversationContext,
        startNanos: Long
    ): NabdAiReply {
        val countLabel = formatRepetitionExplanation(match.repetition, match.categoryId)
        val sourceLine = if (match.hadithNumber.isNotBlank()) {
            "${match.source} (رقم ${match.hadithNumber})"
        } else {
            match.source
        }

        val verifiedContext = buildString {
            append("القسم: ").append(match.categoryName).append('\n')
            append("نص الذكر: ").append(match.dhikrText).append('\n')
            append("عدد التكرار: ").append(countLabel).append('\n')
            append("الفضل: ").append(match.shortVirtue).append('\n')
            if (match.detailedExplanation.isNotBlank()) {
                append("الشرح: ").append(match.detailedExplanation).append('\n')
            }
            append("المصدر: ").append(sourceLine)
        }

        val gemmaIntro = gemmaEngine?.formulateGroundedAnswer(query, verifiedContext, context.recentTurns)
        val usedGemma = !gemmaIntro.isNullOrBlank()
        val finalAnswer = buildString {
            if (usedGemma) {
                append(gemmaIntro).append("\n\n")
            } else {
                append("من **").append(match.categoryName).append("**:\n\n")
            }
            append("«").append(match.dhikrText).append("»\n\n")
            append("• **كم مرة يُقال:** ").append(countLabel).append('\n')
            if (match.shortVirtue.isNotBlank()) {
                append("• **الفضل:** ").append(match.shortVirtue).append('\n')
            }
            append("• **المصدر:** ").append(sourceLine)
        }

        return NabdAiReply(
            answerText = finalAnswer.trim(),
            sourceBadge = "المصدر: $sourceLine (محلي بدون إنترنت)",
            sourceType = NabdAiSourceType.LOCAL_ADHKAR,
            linkedCategoryId = match.categoryId,
            linkedDhikrId = match.dhikrId,
            linkedDhikrIndex = match.indexInCategory,
            matchedDhikrText = match.dhikrText,
            matchedSource = match.source,
            matchedHadithNumber = match.hadithNumber,
            matchedRepetition = match.repetition,
            matchedShortVirtue = match.shortVirtue,
            matchedDetailedExplanation = match.detailedExplanation,
            matchedVocabulary = match.vocabulary,
            usedGemma3 = usedGemma,
            latencyMs = elapsedMs(startNanos)
        )
    }

    private fun detectQuestionAspect(normQuery: String): QuestionAspect = when {
        REPETITION_QUESTION_REGEX.containsMatchIn(normQuery) -> QuestionAspect.REPETITION_COUNT
        MEANING_QUESTION_REGEX.containsMatchIn(normQuery) -> QuestionAspect.MEANING
        VIRTUE_QUESTION_REGEX.containsMatchIn(normQuery) -> QuestionAspect.VIRTUE
        TIMING_QUESTION_REGEX.containsMatchIn(normQuery) -> QuestionAspect.TIMING
        SOURCE_QUESTION_REGEX.containsMatchIn(normQuery) -> QuestionAspect.SOURCE
        else -> QuestionAspect.NONE
    }

    private fun hasStandaloneTopic(normQuery: String): Boolean {
        var stripped = REPETITION_QUESTION_REGEX.replace(normQuery, " ")
        stripped = MEANING_QUESTION_REGEX.replace(stripped, " ")
        stripped = VIRTUE_QUESTION_REGEX.replace(stripped, " ")
        stripped = TIMING_QUESTION_REGEX.replace(stripped, " ")
        stripped = SOURCE_QUESTION_REGEX.replace(stripped, " ")
        val remaining = stripped.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in ARABIC_STOP_WORDS && it !in setOf("ده", "دي", "دا", "الذكر", "الدعاء", "الكلمه", "الحديث", "مره", "مرات", "امتي", "ازاي", "هو", "هي") }
        return remaining.isNotEmpty()
    }

    private fun formatRepetitionExplanation(repetition: Int, categoryId: String): String {
        val isOpenSituation = categoryId in setOf("worry_azkar", "distress_azkar", "anger_azkar", "istikhara_azkar", "sick_azkar")
        return when {
            repetition == 1 && isOpenSituation ->
                "ثبت في السنة قولُه مرةً واحدةً بيقين وحضور قلب، ويُستحب تكراره (3 مرات أو أكثر) بخشوع حتى يطمئن القلب"
            repetition == 1 -> "مرة واحدة (1×)"
            repetition == 2 -> "مرتين (2×)"
            repetition in 3..10 -> "$repetition مرات ($repetition×)"
            else -> "$repetition مرة ($repetition×)"
        }
    }

    private fun countOrderedSubsequence(pattern: List<String>, textTokens: List<String>): Int {
        var pos = 0
        var matched = 0
        for (p in pattern) {
            for (i in pos until textTokens.size) {
                if (textTokens[i] == p || textTokens[i].contains(p)) {
                    matched++
                    pos = i + 1
                    break
                }
            }
        }
        return matched
    }

    private fun toLocalMatch(
        cat: AzkarCategory,
        idx: Int,
        azkar: Azkar,
        meta: OfflineDhikrMetadata?,
        score: Float
    ): LocalDhikrMatch = LocalDhikrMatch(
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

    private fun stripAspectAndCompletionWords(normQuery: String): String {
        var stripped = REPETITION_QUESTION_REGEX.replace(normQuery, " ")
        stripped = MEANING_QUESTION_REGEX.replace(stripped, " ")
        stripped = VIRTUE_QUESTION_REGEX.replace(stripped, " ")
        stripped = TIMING_QUESTION_REGEX.replace(stripped, " ")
        stripped = SOURCE_QUESTION_REGEX.replace(stripped, " ")
        stripped = COMPLETION_COMMAND_REGEX.replace(stripped, " ")
        return stripped.replace(Regex("\\s+"), " ").trim()
    }

    private fun extractDorarSearchKeywords(raw: String): String {
        val cleaned = raw
            .replace(
                Regex("(هل\\s+حديث|ما\\s+صحة\\s+حديث|صحة\\s+حديث|تخريج\\s+حديث|درجة\\s+حديث|تحقق\\s+من\\s+حديث|صحيح\\s+ام\\s+ضعيف|صحيح\\s+أم\\s+ضعيف|صحيح؟|في\\s+الدرر\\s+السنية|أقوله\\s+كام\\s+مرة|اقوله\\s+كام\\s+مره|كام\\s+مرة|كم\\s+مرة|عايز\\s+أعرف|عايز\\s+اعرف|اذكر\\s+حديث|ما\\s+هو\\s+حديث|بحديث|عن\\s+حاجة\\s+معينة)"),
                " "
            )
            .replace(Regex("[؟،.!]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { raw.trim() }
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
