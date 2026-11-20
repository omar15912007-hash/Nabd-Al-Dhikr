package com.nabdaldhikr.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nabdaldhikr.app.data.model.AzkarCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Offline Semantic Search Engine for Nabd Al-Dhikr (نبض الذكر).
 *
 * Architecture:
 *  - Neural Path (when `nabd_ai_minilm.onnx`, `tokenizer.json`, `vectors_[lang].bin`,
 *    and `db_[lang].json` are downloaded):
 *      Encodes query with quantized INT8 `paraphrase-multilingual-MiniLM-L12-v2` via
 *      ONNX Runtime Android (ARM NEON MLAS kernels), then runs raw unit-vector dot-product
 *      Cosine Similarity against pre-computed `vectors_[lang].bin` in <10ms.
 *  - Guardrail & Threshold:
 *      Enforces a strict `0.65f` similarity threshold AND blocks explicit Fiqh/Fatwa/sectarian
 *      queries with a polite Arabic message.
 *  - Lightweight Offline Fallback (when model is not yet downloaded):
 *      Uses Arabic Tashkeel normalization + emotional/dialectal synonym expansion over
 *      bundled `ai_metadata_ar.json` so core UI search & ✨ explanations work on Day 1.
 */
class OfflineDhikrAI(
    private val gson: Gson = Gson()
) : AutoCloseable {

    companion object {
        const val SIMILARITY_THRESHOLD: Float = 0.65f
        private const val VECTOR_MAGIC_INT: Int = 0x4442414E // "NABD" in Little-Endian
        private const val EXPECTED_VERSION: Int = 1
        private const val DTYPE_FP32: Int = 0
        private const val DTYPE_FP16: Int = 1

        const val POLITE_FALLBACK_MESSAGE_AR: String =
            "عذراً، أنا مساعد مخصّص فقط للأذكار النبوية الصحيحة، معاني كلماتها، أوقاتها وفضلها من حصن المسلم والصحيحين. " +
                "لا أقدّم فتاوى فقهية أو أحكام الحلال والحرام، ويمكنك سؤالي عن أي ذكر أو حالة شعورية وسأرشدك للذكر المأثور المناسب."

        private val TASHKEEL_REGEX =
            Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED]")

        private val FATWA_BLOCK_REGEX = Regex(
            pattern = "(فتو[ىي]|استفتاء|ما\\s+حكم|حكم\\s+شرعي|هل\\s+يجوز|يجوز\\s+لي|حلال\\s+ام\\s+حرام|حلال\\s+ولا\\s+حرام|حرام\\s+ولا\\s+حلال|مذهب|مذاهب|طائف|بدع[ةه]|طلاق|ميراث|زكاة\\s+المال|نصاب\\s+الزكاة|fatwa|is\\s+it\\s+haram|is\\s+it\\s+halal|fiqh\\s+ruling|sectarian)",
            option = RegexOption.IGNORE_CASE
        )

        // Conversational filler tokens ignored in fallback lexical denominator
        private val ARABIC_FILLER_TOKENS: Set<String> = setOf(
            "جدا", "عايز", "عاوز", "ابي", "ابغي", "وش", "شو", "بدي", "اقول", "ايه",
            "لما", "عشان", "علشان", "انا", "حاسس", "شاعر", "محتاج", "ذكر", "دعاء",
            "عن", "في", "من", "الي", "علي", "هذا", "هذه", "كيف", "متي", "ما", "هل", "يا"
        )

        // Dialectal & emotional concept clusters for lightweight offline fallback before neural download
        private val EMOTION_EXPANSIONS: Map<String, List<String>> = mapOf(
            "قلقان" to listOf("هم", "حزن", "كرب", "ضيق", "سكينه"),
            "مخنوق" to listOf("هم", "حزن", "كرب", "ضيق", "شرح"),
            "متضايق" to listOf("هم", "حزن", "كرب", "ضيق"),
            "حزين" to listOf("هم", "حزن", "كرب", "ربيع"),
            "خايف" to listOf("حفظ", "امان", "روعاتي", "تحصين", "استعاذه"),
            "ارق" to listOf("نوم", "فراش", "ليل", "كرسي"),
            "انام" to listOf("نوم", "فراش", "كوابيس", "ليل"),
            "كوابيس" to listOf("نوم", "فراش", "حفظ", "معوذات"),
            "صحيت" to listOf("استيقاظ", "احيانا", "نشور"),
            "متعصب" to listOf("غضب", "شيطان", "سكوت", "هدوء"),
            "غضبان" to listOf("غضب", "شيطان", "سكوت"),
            "محتار" to listOf("استخاره", "قرار", "خيره"),
            "مسافر" to listOf("سفر", "ركوب", "طريق", "سخر"),
            "طياره" to listOf("سفر", "ركوب", "سخر"),
            "سياره" to listOf("سفر", "ركوب", "سخر"),
            "مريض" to listOf("مريض", "شفاء", "رقيه", "طهور", "باس"),
            "تعبان" to listOf("مريض", "شفاء", "رقيه", "عافيه", "وجع"),
            "وجع" to listOf("رقيه", "شفاء", "الم", "احاذر"),
            "حسد" to listOf("رقيه", "عين", "فلق", "حاسد", "تحصين"),
            "عين" to listOf("رقيه", "عين", "فلق", "حاسد", "لامه"),
            "مطر" to listOf("مطر", "صيبا", "سماء", "غيث"),
            "رعد" to listOf("رعد", "تسبيح", "خيفته"),
            "عاصفه" to listOf("ريح", "رياح", "خيرها"),
            "هوا" to listOf("ريح", "رياح"),
            "سوق" to listOf("سوق", "رزق", "حلال", "دين"),
            "ديون" to listOf("دين", "ضلع", "فقر", "حلال", "هم"),
            "رزق" to listOf("رزق", "حلال", "طيب", "سوق", "فضل"),
            "امتحان" to listOf("اشرح", "صدري", "يسر", "علم", "توفيق"),
            "مذاكره" to listOf("علم", "اشرح", "صدري", "يسر"),
            "صلاه" to listOf("صلاه", "تسبيح", "سلام", "استغفر"),
            "اذان" to listOf("اذان", "مؤذن", "وسيله", "شفاعه"),
            "مسجد" to listOf("مسجد", "رحمه", "فضل"),
            "بيت" to listOf("منزل", "بيت", "ولجنا", "خروج"),
            "حمام" to listOf("خلاء", "خبث", "غفرانك"),
            "اكل" to listOf("طعام", "اطعمنا", "بسم", "افطار"),
            "صائم" to listOf("افطار", "ظما", "عروق", "طعام"),
            "لبس" to listOf("ثوب", "كساني", "جديد"),
            "هدوم" to listOf("ثوب", "كساني", "جديد"),
            "عطس" to listOf("عطاس", "يرحمك", "بالكم"),
            "صباح" to listOf("صباح", "اصبحنا", "فجر"),
            "مساء" to listOf("مساء", "امسينا", "ليل")
        )
    }

    private data class VectorIndex(
        val languageCode: String,
        val count: Int,
        val dim: Int,
        val dhikrIndices: IntArray,
        val qaIndices: IntArray,
        val vectors: FloatArray,
        val entries: List<AiDhikrEntry>
    )

    private val lock = Any()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var ortSession: OrtSession? = null

    @Volatile
    private var tokenizer: SentencePieceTokenizer? = null

    @Volatile
    private var activeIndex: VectorIndex? = null

    val isNeuralReady: Boolean
        get() = ortSession != null && tokenizer != null && activeIndex != null

    val loadedLanguageCode: String?
        get() = activeIndex?.languageCode

    /**
     * Loads the downloaded INT8 ONNX model, HuggingFace tokenizer, pre-computed vector
     * index (`vectors_[lang].bin`), and language dataset (`db_[lang].json`).
     */
    suspend fun loadNeuralPack(
        modelFile: File,
        tokenizerFile: File,
        vectorsFile: File,
        datasetFile: File,
        languageCode: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!modelFile.isFile || !tokenizerFile.isFile || !vectorsFile.isFile || !datasetFile.isFile) {
            return@withContext Result.failure(IllegalStateException("ملفات النموذج أو حزمة اللغة غير مكتملة."))
        }

        try {
            val loadedTokenizer = SentencePieceTokenizer.fromFile(tokenizerFile)
            val entriesType = object : TypeToken<List<AiDhikrEntry>>() {}.type
            val entries: List<AiDhikrEntry> = datasetFile.bufferedReader(Charsets.UTF_8).use { reader ->
                gson.fromJson(reader, entriesType)
            } ?: emptyList()

            val loadedIndex = readVectorIndex(vectorsFile, languageCode, entries)

            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                runCatching { addConfigEntry("session.intra_op.allow_spinning", "1") }
            }
            val session = env.createSession(modelFile.absolutePath, options)

            synchronized(lock) {
                ortSession?.close()
                ortEnv = env
                ortSession = session
                tokenizer = loadedTokenizer
                activeIndex = loadedIndex
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes semantic search. Uses INT8 ONNX neural engine when loaded, otherwise falls back
     * to lightweight Arabic semantic-synonym matching over bundled metadata.
     * Strictly blocks fatwas and enforces the `0.65f` similarity threshold.
     */
    suspend fun search(
        query: String,
        categories: List<AzkarCategory>,
        offlineMetadata: Map<String, OfflineDhikrMetadata>,
        topK: Int = 3
    ): AiQueryOutcome = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext AiQueryOutcome.NoData

        val startNanos = System.nanoTime()
        if (isFatwaOrOffTopicQuery(trimmed)) {
            val latencyMs = (System.nanoTime() - startNanos) / 1_000_000L
            return@withContext AiQueryOutcome.BlockedOutOfScope(
                message = POLITE_FALLBACK_MESSAGE_AR,
                topScore = 0f,
                latencyMs = latencyMs
            )
        }

        val neuralOutcome = runNeuralSearchIfReady(trimmed, categories, topK)
        if (neuralOutcome != null) return@withContext neuralOutcome

        return@withContext runLightweightOfflineSearch(trimmed, categories, offlineMetadata, topK, startNanos)
    }

    fun isFatwaOrOffTopicQuery(query: String): Boolean {
        val normalized = normalizeArabic(query)
        return FATWA_BLOCK_REGEX.containsMatchIn(normalized) || FATWA_BLOCK_REGEX.containsMatchIn(query)
    }

    private fun runNeuralSearchIfReady(
        query: String,
        categories: List<AzkarCategory>,
        topK: Int
    ): AiQueryOutcome? {
        val env: OrtEnvironment
        val session: OrtSession
        val tok: SentencePieceTokenizer
        val index: VectorIndex

        synchronized(lock) {
            env = ortEnv ?: return null
            session = ortSession ?: return null
            tok = tokenizer ?: return null
            index = activeIndex ?: return null
        }

        val startNanos = System.nanoTime()
        val queryVector = encodeQueryOnnx(env, session, tok, query, index.dim)
            ?: return AiQueryOutcome.NoData

        // Fast linear dot-product scan over unit-normalized vectors (< 2ms for ~3,000 x 384)
        val dim = index.dim
        val vectors = index.vectors
        val bestByDhikr = HashMap<Int, Pair<Float, Int>>() // dhikrIdx -> (bestScore, bestQaIdx)

        var offset = 0
        for (i in 0 until index.count) {
            var dot = 0f
            for (d in 0 until dim) {
                dot += queryVector[d] * vectors[offset + d]
            }
            offset += dim

            val dhikrIdx = index.dhikrIndices[i]
            val current = bestByDhikr[dhikrIdx]
            if (current == null || dot > current.first) {
                bestByDhikr[dhikrIdx] = dot to index.qaIndices[i]
            }
        }

        val latencyMs = (System.nanoTime() - startNanos) / 1_000_000L
        val ranked = bestByDhikr.entries
            .sortedByDescending { it.value.first }
            .take(topK)

        val top = ranked.firstOrNull()
        if (top == null || top.value.first < SIMILARITY_THRESHOLD) {
            return AiQueryOutcome.BlockedOutOfScope(
                message = POLITE_FALLBACK_MESSAGE_AR,
                topScore = top?.value?.first ?: 0f,
                latencyMs = latencyMs
            )
        }

        val matches = ranked
            .filter { it.value.first >= SIMILARITY_THRESHOLD }
            .mapNotNull { (dhikrIdx, scoreAndQa) ->
                val entry = index.entries.getOrNull(dhikrIdx) ?: return@mapNotNull null
                val (categoryId, localDhikrId) = resolveAppLocation(entry, categories)
                val qaAnswer = entry.qaPairs.getOrNull(scoreAndQa.second)?.botAnswer
                    ?: entry.qaPairs.firstOrNull()?.botAnswer
                    ?: entry.detailedExplanation

                SemanticMatch(
                    dhikrId = localDhikrId,
                    categoryId = categoryId,
                    categoryName = entry.category,
                    orderIndex = entry.orderIndex,
                    dhikrText = entry.dhikrText,
                    shortVirtue = entry.shortVirtueForCard,
                    detailedExplanation = entry.detailedExplanation,
                    botAnswer = qaAnswer,
                    vocabulary = entry.vocabularyExplanation,
                    suggestedFollowups = entry.suggestedFollowups,
                    similarityScore = scoreAndQa.first,
                    usedNeuralEngine = true
                )
            }

        if (matches.isEmpty()) return AiQueryOutcome.NoData
        return AiQueryOutcome.Match(
            bestMatch = matches.first(),
            alternatives = matches.drop(1),
            latencyMs = latencyMs
        )
    }

    private fun encodeQueryOnnx(
        env: OrtEnvironment,
        session: OrtSession,
        tok: SentencePieceTokenizer,
        query: String,
        expectedDim: Int
    ): FloatArray? {
        val encoded = tok.encode(query)
        val seqLen = encoded.inputIds.size.toLong()
        val shape = longArrayOf(1L, seqLen)

        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape)
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape)

        return try {
            val inputs = HashMap<String, OnnxTensor>(2).apply {
                put("input_ids", inputIdsTensor)
                put("attention_mask", maskTensor)
            }
            session.run(inputs).use { result ->
                val rawValue = result[0].value
                val vector = when (rawValue) {
                    is Array<*> -> extractPooledEmbedding(rawValue, encoded.attentionMask, expectedDim)
                    else -> null
                } ?: return null
                l2Normalize(vector)
            }
        } catch (_: Exception) {
            null
        } finally {
            inputIdsTensor.close()
            maskTensor.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractPooledEmbedding(
        rawValue: Array<*>,
        attentionMask: LongArray,
        expectedDim: Int
    ): FloatArray? {
        if (rawValue.isEmpty()) return null
        val first = rawValue[0] ?: return null

        // Case 1: Model outputs [1, 384] directly (from NormalizedSentenceEncoder in prepare_model.py)
        if (first is FloatArray) {
            return first.copyOf()
        }

        // Case 2: Raw transformer outputs [1, seq_len, 384] -> perform attention-masked Mean Pooling
        if (first is Array<*>) {
            val tokenMatrix = first as? Array<FloatArray> ?: return null
            val pooled = FloatArray(expectedDim)
            var validTokens = 0f
            for (t in tokenMatrix.indices) {
                if (t < attentionMask.size && attentionMask[t] == 0L) continue
                val row = tokenMatrix[t]
                if (row.size < expectedDim) continue
                for (d in 0 until expectedDim) {
                    pooled[d] += row[d]
                }
                validTokens += 1f
            }
            if (validTokens <= 0f) return null
            for (d in 0 until expectedDim) {
                pooled[d] /= validTokens
            }
            return pooled
        }
        return null
    }

    private fun runLightweightOfflineSearch(
        query: String,
        categories: List<AzkarCategory>,
        offlineMetadata: Map<String, OfflineDhikrMetadata>,
        topK: Int,
        startNanos: Long
    ): AiQueryOutcome {
        if (categories.isEmpty()) return AiQueryOutcome.NoData

        val normQuery = normalizeArabic(query)
        val rawTokens = normQuery.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (rawTokens.isEmpty()) return AiQueryOutcome.NoData

        val meaningfulTokens = rawTokens.filter { token ->
            token !in ARABIC_FILLER_TOKENS && stripArabicPrefix(token) !in ARABIC_FILLER_TOKENS
        }.ifEmpty { rawTokens }

        val scored = ArrayList<SemanticMatch>()
        for (cat in categories) {
            val normCat = normalizeArabic(cat.nameAr + " " + cat.nameEn)
            cat.azkarList.forEachIndexed { idx, azkar ->
                val key = "${cat.id}_${azkar.id}"
                val meta = offlineMetadata[key]
                val searchable = buildString {
                    append(normCat).append(' ')
                    append(normalizeArabic(azkar.text)).append(' ')
                    append(normalizeArabic(azkar.virtue)).append(' ')
                    if (meta != null) {
                        append(normalizeArabic(meta.detailedExplanation)).append(' ')
                        meta.keywords.forEach { append(normalizeArabic(it)).append(' ') }
                    }
                }

                var tokenScoreSum = 0f
                var categoryHit = false
                for (token in meaningfulTokens) {
                    val stem = stripArabicPrefix(token)
                    val synonyms = EMOTION_EXPANSIONS[token] ?: EMOTION_EXPANSIONS[stem]

                    if (normCat.contains(token) || (stem.length >= 3 && normCat.contains(stem))) {
                        categoryHit = true
                    }

                    val directMatch = searchable.contains(token) || (stem.length >= 3 && searchable.contains(stem))
                    if (directMatch) {
                        tokenScoreSum += 1.0f
                    } else if (!synonyms.isNullOrEmpty()) {
                        val synHits = synonyms.count { syn -> searchable.contains(syn) }
                        if (synonyms.any { syn -> normCat.contains(syn) }) {
                            categoryHit = true
                        }
                        if (synHits >= 2) {
                            tokenScoreSum += 0.92f
                        } else if (synHits == 1) {
                            tokenScoreSum += 0.72f
                        }
                    }
                }

                val baseScore = tokenScoreSum / meaningfulTokens.size.toFloat()
                val categoryBoost = if (categoryHit && tokenScoreSum > 0f) 0.12f else 0f
                val score = (baseScore + categoryBoost).coerceAtMost(0.98f)

                if (score >= SIMILARITY_THRESHOLD) {
                    scored.add(
                        SemanticMatch(
                            dhikrId = azkar.id,
                            categoryId = cat.id,
                            categoryName = cat.nameAr,
                            orderIndex = idx + 1,
                            dhikrText = azkar.text,
                            shortVirtue = meta?.shortVirtue?.ifBlank { azkar.virtue } ?: azkar.virtue,
                            detailedExplanation = meta?.detailedExplanation.orEmpty(),
                            botAnswer = meta?.botAnswer ?: azkar.virtue,
                            vocabulary = meta?.vocabulary.orEmpty(),
                            suggestedFollowups = meta?.suggestedFollowups.orEmpty(),
                            similarityScore = score,
                            usedNeuralEngine = false
                        )
                    )
                }
            }
        }

        val latencyMs = (System.nanoTime() - startNanos) / 1_000_000L
        val sorted = scored.sortedByDescending { it.similarityScore }.take(topK)
        if (sorted.isEmpty()) {
            return AiQueryOutcome.BlockedOutOfScope(
                message = POLITE_FALLBACK_MESSAGE_AR,
                topScore = 0f,
                latencyMs = latencyMs
            )
        }

        return AiQueryOutcome.Match(
            bestMatch = sorted.first(),
            alternatives = sorted.drop(1),
            latencyMs = latencyMs
        )
    }

    private fun readVectorIndex(
        file: File,
        languageCode: String,
        entries: List<AiDhikrEntry>
    ): VectorIndex {
        val rawBytes = file.readBytes()
        require(rawBytes.size >= 20) { "Vector binary file too small: ${file.name}" }

        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        require(magic == VECTOR_MAGIC_INT) { "Invalid vector file magic header in ${file.name}" }

        val version = buffer.int
        require(version == EXPECTED_VERSION) { "Unsupported vector format version: $version" }

        val count = buffer.int
        val dim = buffer.int
        val dtype = buffer.int
        require(count > 0 && dim > 0) { "Empty vector table in ${file.name}" }

        val dhikrIndices = IntArray(count)
        val qaIndices = IntArray(count)
        for (i in 0 until count) {
            dhikrIndices[i] = buffer.int
            qaIndices[i] = buffer.int
        }

        val totalFloats = count * dim
        val vectors = FloatArray(totalFloats)
        when (dtype) {
            DTYPE_FP32 -> {
                for (i in 0 until totalFloats) {
                    vectors[i] = buffer.float
                }
            }
            DTYPE_FP16 -> {
                for (i in 0 until totalFloats) {
                    vectors[i] = halfBitsToFloat(buffer.short.toInt() and 0xFFFF)
                }
            }
            else -> throw IllegalArgumentException("Unsupported vector dtype code: $dtype")
        }

        return VectorIndex(
            languageCode = languageCode,
            count = count,
            dim = dim,
            dhikrIndices = dhikrIndices,
            qaIndices = qaIndices,
            vectors = vectors,
            entries = entries
        )
    }

    private fun resolveAppLocation(
        entry: AiDhikrEntry,
        categories: List<AzkarCategory>
    ): Pair<String, Int> {
        // IDs follow "<lang>_<categoryId>_<orderIndex>"
        val parts = entry.id.split("_")
        if (parts.size >= 3) {
            val candidateCatId = parts.subList(1, parts.size - 1).joinToString("_")
            val matchedCat = categories.firstOrNull { it.id == candidateCatId }
            if (matchedCat != null) {
                val azkar = matchedCat.azkarList.getOrNull((entry.orderIndex - 1).coerceAtLeast(0))
                if (azkar != null) return matchedCat.id to azkar.id
            }
        }
        val fallbackCat = categories.firstOrNull {
            it.nameAr == entry.category || it.nameEn.equals(entry.category, ignoreCase = true)
        }
        if (fallbackCat != null) {
            val azkar = fallbackCat.azkarList.getOrNull((entry.orderIndex - 1).coerceAtLeast(0))
            if (azkar != null) return fallbackCat.id to azkar.id
        }
        return "" to -1
    }

    override fun close() {
        synchronized(lock) {
            ortSession?.close()
            ortSession = null
            tokenizer = null
            activeIndex = null
        }
    }

    /**
     * Pure IEEE-754 binary16 (FP16) to float32 conversion for API 26+ compatibility.
     */
    internal fun halfBitsToFloat(hbits: Int): Float {
        val sign = (hbits and 0x8000) ushr 15
        val exp = (hbits and 0x7C00) ushr 10
        val mant = hbits and 0x03FF
        val signFactor = if (sign == 1) -1f else 1f
        return when (exp) {
            0 -> signFactor * (mant / 1024f) * 2f.pow(-14)
            31 -> if (mant == 0) signFactor * Float.POSITIVE_INFINITY else Float.NaN
            else -> signFactor * (1f + mant / 1024f) * 2f.pow(exp - 15)
        }
    }

    internal fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq).coerceAtLeast(1e-9f)
        for (i in vec.indices) vec[i] /= norm
        return vec
    }

    internal fun normalizeArabic(text: String): String {
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
