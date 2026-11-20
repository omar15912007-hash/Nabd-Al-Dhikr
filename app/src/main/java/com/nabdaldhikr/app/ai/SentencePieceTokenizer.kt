package com.nabdaldhikr.app.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.Normalizer

/**
 * Pure-Kotlin HuggingFace `tokenizer.json` tokenizer supporting XLM-RoBERTa SentencePiece
 * Unigram (used by `paraphrase-multilingual-MiniLM-L12-v2`) via Viterbi segmentation,
 * with fallback support for BERT WordPiece models.
 *
 * Keeps APK ultra-compact by avoiding heavy native C++/Rust tokenizer binaries.
 */
class SentencePieceTokenizer private constructor(
    private val mode: Mode,
    private val unigramScores: Map<String, Pair<Int, Float>>,
    private val wordPieceVocab: Map<String, Int>,
    private val maxPieceLen: Int,
    private val bosId: Int,
    private val eosId: Int,
    private val unkId: Int,
    private val continuingSubwordPrefix: String
) {
    enum class Mode { UNIGRAM, WORDPIECE }

    data class EncodedSequence(
        val inputIds: LongArray,
        val attentionMask: LongArray
    )

    companion object {
        private const val SPIECE_UNDERLINE = "\u2581"
        private const val DEFAULT_MAX_LENGTH = 128

        fun fromFile(tokenizerJsonFile: File): SentencePieceTokenizer {
            require(tokenizerJsonFile.isFile) { "Tokenizer file not found: ${tokenizerJsonFile.path}" }
            return fromJsonString(tokenizerJsonFile.readText(Charsets.UTF_8))
        }

        fun fromJsonString(json: String): SentencePieceTokenizer {
            val root = JsonParser.parseString(json).asJsonObject
            val model = root.getAsJsonObject("model")
                ?: throw IllegalArgumentException("Invalid tokenizer.json: missing 'model' block")
            val modelType = model.get("type")?.asString ?: "Unigram"

            return if (modelType.equals("WordPiece", ignoreCase = true)) {
                parseWordPiece(model)
            } else {
                parseUnigram(root, model)
            }
        }

        private fun parseUnigram(root: JsonObject, model: JsonObject): SentencePieceTokenizer {
            val vocabArray = model.getAsJsonArray("vocab")
                ?: throw IllegalArgumentException("Unigram tokenizer missing 'vocab' array")

            val scores = HashMap<String, Pair<Int, Float>>(vocabArray.size())
            var maxLen = 1
            for (index in 0 until vocabArray.size()) {
                val entry = vocabArray[index].asJsonArray
                val piece = entry[0].asString
                val score = entry[1].asFloat
                scores[piece] = index to score
                if (piece.length > maxLen) maxLen = piece.length
            }

            var bosId = scores["<s>"]?.first ?: 0
            var eosId = scores["</s>"]?.first ?: 2
            var unkId = model.get("unk_id")?.asInt ?: (scores["<unk>"]?.first ?: 3)

            root.getAsJsonArray("added_tokens")?.forEach { elem ->
                val obj = elem.asJsonObject
                val content = obj.get("content")?.asString ?: return@forEach
                val id = obj.get("id")?.asInt ?: return@forEach
                when (content) {
                    "<s>" -> bosId = id
                    "</s>" -> eosId = id
                    "<unk>" -> unkId = id
                }
            }

            return SentencePieceTokenizer(
                mode = Mode.UNIGRAM,
                unigramScores = scores,
                wordPieceVocab = emptyMap(),
                maxPieceLen = maxLen.coerceAtMost(32),
                bosId = bosId,
                eosId = eosId,
                unkId = unkId,
                continuingSubwordPrefix = ""
            )
        }

        private fun parseWordPiece(model: JsonObject): SentencePieceTokenizer {
            val vocabObj = model.getAsJsonObject("vocab")
                ?: throw IllegalArgumentException("WordPiece tokenizer missing 'vocab' object")
            val vocab = HashMap<String, Int>(vocabObj.size())
            for ((token, value) in vocabObj.entrySet()) {
                vocab[token] = value.asInt
            }
            val prefix = model.get("continuing_subword_prefix")?.asString ?: "##"
            return SentencePieceTokenizer(
                mode = Mode.WORDPIECE,
                unigramScores = emptyMap(),
                wordPieceVocab = vocab,
                maxPieceLen = 32,
                bosId = vocab["[CLS]"] ?: 101,
                eosId = vocab["[SEP]"] ?: 102,
                unkId = vocab["[UNK]"] ?: 100,
                continuingSubwordPrefix = prefix
            )
        }
    }

    fun encode(rawText: String, maxLength: Int = DEFAULT_MAX_LENGTH): EncodedSequence {
        val limit = maxLength.coerceAtLeast(4)
        val normalized = Normalizer.normalize(rawText.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")

        val contentIds = if (normalized.isEmpty()) {
            emptyList()
        } else if (mode == Mode.UNIGRAM) {
            encodeUnigramViterbi(normalized, limit - 2)
        } else {
            encodeWordPiece(normalized, limit - 2)
        }

        val totalLen = contentIds.size + 2
        val inputIds = LongArray(totalLen)
        val attentionMask = LongArray(totalLen) { 1L }
        inputIds[0] = bosId.toLong()
        for (i in contentIds.indices) {
            inputIds[i + 1] = contentIds[i].toLong()
        }
        inputIds[totalLen - 1] = eosId.toLong()

        return EncodedSequence(inputIds = inputIds, attentionMask = attentionMask)
    }

    private fun encodeUnigramViterbi(text: String, maxContentTokens: Int): List<Int> {
        val prefixed = SPIECE_UNDERLINE + text.replace(" ", SPIECE_UNDERLINE)
        val n = prefixed.length
        if (n == 0) return emptyList()

        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val prevPos = IntArray(n + 1) { -1 }
        val bestTokenId = IntArray(n + 1) { unkId }
        bestScore[0] = 0f

        for (i in 0 until n) {
            val base = bestScore[i]
            if (base == Float.NEGATIVE_INFINITY) continue

            val endLimit = minOf(n, i + maxPieceLen)
            var matchedAny = false
            for (end in (i + 1)..endLimit) {
                val sub = prefixed.substring(i, end)
                val entry = unigramScores[sub] ?: continue
                matchedAny = true
                val candidateScore = base + entry.second
                if (candidateScore > bestScore[end]) {
                    bestScore[end] = candidateScore
                    prevPos[end] = i
                    bestTokenId[end] = entry.first
                }
            }

            if (!matchedAny) {
                val next = i + 1
                val fallbackScore = base - 25.0f
                if (fallbackScore > bestScore[next]) {
                    bestScore[next] = fallbackScore
                    prevPos[next] = i
                    bestTokenId[next] = unkId
                }
            }
        }

        val reversed = ArrayList<Int>(minOf(n, maxContentTokens))
        var cursor = n
        while (cursor > 0 && prevPos[cursor] >= 0) {
            reversed.add(bestTokenId[cursor])
            cursor = prevPos[cursor]
        }
        reversed.reverse()
        return if (reversed.size > maxContentTokens) reversed.subList(0, maxContentTokens) else reversed
    }

    private fun encodeWordPiece(text: String, maxContentTokens: Int): List<Int> {
        val output = ArrayList<Int>(maxContentTokens)
        val words = text.lowercase().split(Regex("\\s+"))
        for (word in words) {
            if (output.size >= maxContentTokens) break
            var start = 0
            while (start < word.length && output.size < maxContentTokens) {
                var end = word.length
                var foundId: Int? = null
                while (end > start) {
                    val piece = if (start == 0) {
                        word.substring(start, end)
                    } else {
                        continuingSubwordPrefix + word.substring(start, end)
                    }
                    val id = wordPieceVocab[piece]
                    if (id != null) {
                        foundId = id
                        break
                    }
                    end--
                }
                if (foundId == null) {
                    output.add(unkId)
                    break
                }
                output.add(foundId)
                start = end
            }
        }
        return output
    }
}
