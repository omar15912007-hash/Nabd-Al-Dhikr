package com.nabdaldhikr.app.ai

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 100% Free Direct Dorar.net Hadith Verification Client (ZERO paid APIs, ZERO MCP servers).
 *
 * Queries `https://dorar.net/dorar_api.json?skey=<query>` directly via OkHttp,
 * parses the structured HTML entries returned in `ahadith.result`, and strictly
 * filters for authentic (Sahih / Hasan) narrations ONLY.
 */
class DorarApiClient(
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        private const val DORAR_API_ENDPOINT = "https://dorar.net/dorar_api.json"

        private val AUTHENTIC_MARKERS = listOf(
            "صحيح", "حسن", "متفق عليه", "إسناده صحيح", "إسناده حسن", "ثابت", "جيد", "على شرط"
        )

        private val REJECTED_MARKERS = listOf(
            "ضعيف", "موضوع", "باطل", "منكر", "لا أصل له", "كذب", "واه", "مردود",
            "شاذ", "غير صحيح", "لم يثبت", "مقطوع", "مرسل", "معلول", "متروك", "تالف", "منقطع"
        )

        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val HADITH_BLOCK_REGEX = Regex(
            "<div\\s+class=['\"]hadith['\"][^>]*>(.*?)</div>\\s*<div\\s+class=['\"]hadith-info['\"][^>]*>(.*?)</div>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun searchAuthenticHadith(
        query: String,
        maxResults: Int = 3
    ): Result<List<DorarHadithResult>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext Result.success(emptyList())

        try {
            val url = DORAR_API_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("skey", cleanQuery)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "ar,en;q=0.8")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("تعذر الاتصال بموسوعة الدرر السنية (HTTP ${response.code})")
                    )
                }
                val rawBody = response.body?.string().orEmpty()
                if (rawBody.isBlank()) {
                    return@withContext Result.success(emptyList())
                }
                val parsed = parseDorarResponse(rawBody)
                    .filter { isAuthenticRuling(it.ruling) }
                    .take(maxResults)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Pure deterministic parser for Dorar.net API JSON / JSONP payloads (unit-testable).
     */
    fun parseDorarResponse(rawPayload: String): List<DorarHadithResult> {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Strip optional JSONP wrapper: callback({...});
        val jsonBody = if (!trimmed.startsWith("{")) {
            val firstBrace = trimmed.indexOf('{')
            val lastBrace = trimmed.lastIndexOf('}')
            if (firstBrace in 0 until lastBrace) {
                trimmed.substring(firstBrace, lastBrace + 1)
            } else {
                return emptyList()
            }
        } else {
            trimmed
        }

        val htmlResult = extractHtmlResultField(jsonBody) ?: return emptyList()
        if (htmlResult.isBlank()) return emptyList()

        val results = ArrayList<DorarHadithResult>()
        val matches = HADITH_BLOCK_REGEX.findAll(htmlResult)
        for (match in matches) {
            val rawHadithHtml = match.groupValues[1]
            val rawInfoHtml = match.groupValues[2]

            val hadithText = cleanHtmlText(rawHadithHtml)
                .replace(Regex("^\\d+\\s*-\\s*"), "")
                .trim()
            if (hadithText.isBlank()) continue

            val infoText = cleanHtmlText(rawInfoHtml)
            val narrator = extractInfoField(infoText, "الراوي", listOf("المحدث", "المصدر", "الصفحة أو الرقم", "خلاصة حكم المحدث"))
            val scholar = extractInfoField(infoText, "المحدث", listOf("المصدر", "الصفحة أو الرقم", "خلاصة حكم المحدث"))
            val sourceBook = extractInfoField(infoText, "المصدر", listOf("الصفحة أو الرقم", "خلاصة حكم المحدث"))
            val numberOrPage = extractInfoField(infoText, "الصفحة أو الرقم", listOf("خلاصة حكم المحدث", "التخريج"))
            val ruling = extractInfoField(infoText, "خلاصة حكم المحدث", listOf("التخريج", "شرح الحديث", "أحكام أخرى"))

            results.add(
                DorarHadithResult(
                    hadithText = hadithText,
                    narrator = narrator,
                    scholar = scholar,
                    sourceBook = sourceBook.ifBlank { "الموسوعة الحديثية" },
                    numberOrPage = numberOrPage,
                    ruling = ruling
                )
            )
        }
        return results
    }

    /**
     * Strict authenticity filter: accepts ONLY Sahih / Hasan narrations and rejects any weak/fabricated verdict.
     */
    fun isAuthenticRuling(ruling: String): Boolean {
        val clean = ruling.trim()
        if (clean.isEmpty()) return false
        if (REJECTED_MARKERS.any { clean.contains(it) }) return false
        return AUTHENTIC_MARKERS.any { clean.contains(it) }
    }

    private fun extractHtmlResultField(jsonBody: String): String? {
        return try {
            val root = JsonParser.parseString(jsonBody).asJsonObject
            val ahadith = root.get("ahadith") ?: return null
            when {
                ahadith.isJsonObject -> ahadith.asJsonObject.get("result")?.asString
                ahadith.isJsonPrimitive -> ahadith.asString
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractInfoField(infoText: String, label: String, stopLabels: List<String>): String {
        val marker = "$label:"
        val start = infoText.indexOf(marker)
        if (start == -1) return ""
        val valueStart = start + marker.length
        var end = infoText.length
        for (stop in stopLabels) {
            val idx = infoText.indexOf("$stop:", valueStart)
            if (idx != -1 && idx < end) end = idx
        }
        return infoText.substring(valueStart, end).trim(' ', '\t', '|', '-', '،')
    }

    private fun cleanHtmlText(html: String): String {
        val noTags = HTML_TAG_REGEX.replace(html, " ")
        val decoded = noTags
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
        return MULTI_SPACE_REGEX.replace(decoded, " ").trim()
    }
}
