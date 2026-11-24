package com.example.agent.tools

import android.text.Html
import com.example.agent.ResearchSourceItem
import com.example.agent.TaskMemory
import com.example.agent.ToolFailureKind
import com.example.agent.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

interface ResearchSynthesizer {
    suspend fun synthesizeComparison(
        goal: String,
        criteria: String,
        sources: List<ResearchSourceItem>,
        extractedSnippets: Map<String, String>
    ): String?
}

class WebResearchTools(
    private val httpClient: OkHttpClient = defaultClient(),
    private val wikipediaBaseUrl: String = DEFAULT_WIKIPEDIA_BASE_URL,
    private val duckDuckGoBaseUrl: String = DEFAULT_DDG_BASE_URL,
    private val synthesizer: ResearchSynthesizer? = null
) {

    suspend fun searchWeb(query: String, maxResults: Int = 5): ToolResult = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) {
            return@withContext ToolResult(
                success = false,
                summary = "Search query cannot be blank.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide a search query."
            )
        }

        val collected = mutableListOf<ResearchSourceItem>()
        var lastNetworkError: Throwable? = null

        // 1. DuckDuckGo Instant Answer / Topic Search JSON API
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val ddgUrl = "${duckDuckGoBaseUrl.trimEnd('/')}/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val ddgResult = executeGetText(ddgUrl)
        ddgResult.onSuccess { body ->
            collected.addAll(parseDuckDuckGoJson(body, clean))
        }.onFailure { err ->
            if (err is CancellationException) throw err
            lastNetworkError = err
        }

        // 2. Wikipedia OpenSearch API (real encyclopedic & spec references)
        val wikiUrl = "${wikipediaBaseUrl.trimEnd('/')}/w/api.php?action=opensearch&search=$encoded&limit=${maxResults.coerceIn(1, 8)}&namespace=0&format=json"
        val wikiResult = executeGetText(wikiUrl)
        wikiResult.onSuccess { body ->
            collected.addAll(parseWikipediaOpenSearchJson(body))
        }.onFailure { err ->
            if (err is CancellationException) throw err
            if (lastNetworkError == null) lastNetworkError = err
        }

        val deduplicated = collected
            .distinctBy { it.url.ifBlank { it.title } }
            .take(maxResults.coerceIn(1, 10))

        if (deduplicated.isEmpty()) {
            val isNetworkFailure = lastNetworkError is IOException
            return@withContext ToolResult(
                success = false,
                summary = if (isNetworkFailure) {
                    "Web search failed due to network error for '$clean'."
                } else {
                    "No web research sources matched '$clean'."
                },
                failureKind = if (isNetworkFailure) ToolFailureKind.TRANSIENT_NETWORK else ToolFailureKind.EXECUTION_ERROR,
                errorMessage = lastNetworkError?.localizedMessage
                    ?: "No search results were returned for '$clean'."
            )
        }

        val summaryText = buildString {
            appendLine("Found ${deduplicated.size} sources for \"$clean\":")
            deduplicated.forEachIndexed { idx, src ->
                appendLine("${idx + 1}. ${src.title} (${src.url})")
                if (src.snippet.isNotBlank()) {
                    appendLine("   ${src.snippet.take(180)}")
                }
            }
        }.trim()

        ToolResult(
            success = true,
            summary = summaryText,
            structuredData = buildMap {
                put("query", clean)
                put("result_count", deduplicated.size.toString())
                put("top_url", deduplicated.first().url)
                put("top_title", deduplicated.first().title)
                put("search_summary", summaryText)
            },
            sources = deduplicated
        )
    }

    suspend fun fetchPage(rawUrl: String): ToolResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val parsed = cleanUrl.toHttpUrlOrNull()
            ?: return@withContext ToolResult(
                success = false,
                summary = "Invalid page URL '$cleanUrl'.",
                failureKind = ToolFailureKind.INVALID_ARGS,
                errorMessage = "Provide a valid http/https URL."
            )

        val isLocalTestHost = parsed.host == "127.0.0.1" || parsed.host == "localhost"
        if (parsed.scheme != "https" && !(parsed.scheme == "http" && isLocalTestHost)) {
            return@withContext ToolResult(
                success = false,
                summary = "Only HTTPS public URLs are allowed.",
                failureKind = ToolFailureKind.PROHIBITED,
                errorMessage = "Blocked non-HTTPS external URL '$cleanUrl'."
            )
        }

        executeGetText(cleanUrl).fold(
            onSuccess = { body ->
                val extracted = extractReadablePage(cleanUrl, body)
                if (extracted.text.isBlank()) {
                    return@withContext ToolResult(
                        success = false,
                        summary = "Fetched $cleanUrl but extracted no readable text.",
                        failureKind = ToolFailureKind.EXECUTION_ERROR,
                        errorMessage = "Page contained empty or non-textual content."
                    )
                }
                val item = ResearchSourceItem(
                    title = extracted.title.ifBlank { parsed.host },
                    url = cleanUrl,
                    snippet = extracted.text.take(600)
                )
                ToolResult(
                    success = true,
                    summary = "Extracted ${extracted.text.length} chars from ${item.title}: ${extracted.text.take(240)}",
                    structuredData = mapOf(
                        "url" to cleanUrl,
                        "page_title" to item.title,
                        "extracted_text" to extracted.text.take(4000),
                        "char_count" to extracted.text.length.toString()
                    ),
                    sources = listOf(item)
                )
            },
            onFailure = { err ->
                val kind = if (err is IOException) ToolFailureKind.TRANSIENT_NETWORK else ToolFailureKind.EXECUTION_ERROR
                ToolResult(
                    success = false,
                    summary = "Failed to fetch $cleanUrl",
                    failureKind = kind,
                    errorMessage = err.localizedMessage ?: "HTTP request failed for $cleanUrl"
                )
            }
        )
    }

    suspend fun compareAndSummarize(
        goal: String,
        criteria: String,
        memory: TaskMemory
    ): ToolResult {
        val sources = memory.getSources()
        val extracted = memory.getAllStepOutputs()
            .filterValues { it.success && !it.structuredData["extracted_text"].isNullOrBlank() }
            .mapKeys { (stepId, res) -> res.structuredData["page_title"] ?: stepId }
            .mapValues { (_, res) -> res.structuredData["extracted_text"].orEmpty() }

        if (sources.isEmpty() && extracted.isEmpty()) {
            return ToolResult(
                success = false,
                summary = "No research sources or extracted pages exist to compare.",
                failureKind = ToolFailureKind.EXECUTION_ERROR,
                errorMessage = "Cannot compare without prior fetched research results."
            )
        }

        val aiSynthesis = runCatching {
            synthesizer?.synthesizeComparison(goal, criteria, sources, extracted)
        }.onFailure { err ->
            if (err is CancellationException) throw err
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val comparisonMarkdown = aiSynthesis ?: buildDeterministicComparison(
            goal = goal,
            criteria = criteria,
            sources = sources,
            extractedSnippets = extracted
        )

        return ToolResult(
            success = true,
            summary = "Compared ${sources.size} sources against criteria '${criteria.ifBlank { "overall value & specs" }}'.",
            structuredData = mapOf(
                "comparison_markdown" to comparisonMarkdown,
                "source_count" to sources.size.toString()
            ),
            sources = sources
        )
    }

    fun createResearchReport(
        title: String,
        goal: String,
        memory: TaskMemory
    ): ToolResult {
        val sources = memory.getSources()
        val comparison = memory.getVariable("comparison_markdown").orEmpty()
        val stepSummaries = memory.getAllStepOutputs()
            .filterValues { it.success && it.summary.isNotBlank() }
            .values
            .map { it.summary }

        if (sources.isEmpty() && comparison.isBlank() && stepSummaries.isEmpty()) {
            return ToolResult(
                success = false,
                summary = "No verified findings found in task memory for report generation.",
                failureKind = ToolFailureKind.EXECUTION_ERROR,
                errorMessage = "Execute research steps before generating a report."
            )
        }

        val reportTitle = title.trim().ifBlank { "ALLORA Research Report" }
        val markdown = buildString {
            appendLine("# $reportTitle")
            appendLine()
            appendLine("**Goal:** $goal")
            appendLine()
            if (comparison.isNotBlank()) {
                appendLine(comparison.trim())
                appendLine()
            } else {
                appendLine("## Verified Findings")
                stepSummaries.forEachIndexed { idx, line ->
                    appendLine("${idx + 1}. $line")
                }
                appendLine()
            }
            if (sources.isNotEmpty()) {
                appendLine("## Sources Consulted")
                sources.forEachIndexed { idx, src ->
                    appendLine("${idx + 1}. [${src.title}](${src.url})")
                    if (src.snippet.isNotBlank()) {
                        appendLine("   - ${src.snippet.take(220)}")
                    }
                }
            }
        }.trim()

        return ToolResult(
            success = true,
            summary = "Prepared structured report '$reportTitle' (${sources.size} cited sources).",
            structuredData = mapOf(
                "report_title" to reportTitle,
                "report_markdown" to markdown,
                "source_count" to sources.size.toString()
            ),
            sources = sources
        )
    }

    private fun executeGetText(url: String): Result<String> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ALLORA-Android-Agent/1.0")
            .header("Accept", "application/json, text/html, text/plain")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val code = resp.code
            val body = resp.body?.string().orEmpty()
            if (code in 500..599 || code == 429) {
                throw IOException("Transient HTTP $code from $url")
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP $code returned by $url")
            }
            body.take(MAX_BODY_CHARS)
        }
    }

    private fun parseDuckDuckGoJson(raw: String, query: String): List<ResearchSourceItem> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<ResearchSourceItem>()
        val heading = json.optString("Heading").trim()
        val abstractText = json.optString("AbstractText").trim()
        val abstractUrl = json.optString("AbstractURL").trim()
        if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
            results.add(
                ResearchSourceItem(
                    title = heading.ifBlank { query },
                    url = abstractUrl,
                    snippet = abstractText
                )
            )
        }

        val resultsArr = json.optJSONArray("Results")
        if (resultsArr != null) {
            for (i in 0 until resultsArr.length()) {
                val obj = resultsArr.optJSONObject(i) ?: continue
                val url = obj.optString("FirstURL").trim()
                val text = obj.optString("Text").trim()
                if (url.isNotBlank() && text.isNotBlank()) {
                    results.add(
                        ResearchSourceItem(
                            title = text.substringBefore(" - ").take(90),
                            url = url,
                            snippet = text
                        )
                    )
                }
            }
        }

        val related = json.optJSONArray("RelatedTopics")
        if (related != null) {
            for (i in 0 until related.length()) {
                val item = related.optJSONObject(i) ?: continue
                val text = item.optString("Text").trim()
                val url = item.optString("FirstURL").trim()
                if (text.isNotBlank() && url.isNotBlank()) {
                    results.add(
                        ResearchSourceItem(
                            title = text.substringBefore(" - ").take(90),
                            url = url,
                            snippet = text
                        )
                    )
                } else if (item.has("Topics")) {
                    val nested = item.optJSONArray("Topics") ?: continue
                    for (j in 0 until nested.length()) {
                        val sub = nested.optJSONObject(j) ?: continue
                        val subText = sub.optString("Text").trim()
                        val subUrl = sub.optString("FirstURL").trim()
                        if (subText.isNotBlank() && subUrl.isNotBlank()) {
                            results.add(
                                ResearchSourceItem(
                                    title = subText.substringBefore(" - ").take(90),
                                    url = subUrl,
                                    snippet = subText
                                )
                            )
                        }
                    }
                }
            }
        }
        return results
    }

    private fun parseWikipediaOpenSearchJson(raw: String): List<ResearchSourceItem> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        if (arr.length() < 4) return emptyList()
        val titles = arr.optJSONArray(1) ?: return emptyList()
        val snippets = arr.optJSONArray(2)
        val urls = arr.optJSONArray(3) ?: return emptyList()
        val out = mutableListOf<ResearchSourceItem>()
        for (i in 0 until titles.length()) {
            val title = titles.optString(i).trim()
            val url = urls.optString(i).trim()
            val snippet = snippets?.optString(i)?.trim().orEmpty()
            if (title.isNotBlank() && url.isNotBlank()) {
                out.add(ResearchSourceItem(title = title, url = url, snippet = snippet))
            }
        }
        return out
    }

    private data class ExtractedPage(val title: String, val text: String)

    private fun extractReadablePage(url: String, body: String): ExtractedPage {
        val trimmed = body.trim()
        if (!trimmed.contains("<")) {
            return ExtractedPage(title = url, text = trimmed.replace(Regex("\\s+"), " ").trim())
        }
        val titleMatch = Regex("(?is)<title[^>]*>(.*?)</title>").find(trimmed)
        val rawTitle = titleMatch?.groupValues?.getOrNull(1).orEmpty()
        val cleanTitle = decodeHtml(rawTitle).replace(Regex("\\s+"), " ").trim()

        val withoutScripts = trimmed
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?is)<(br|p|div|li|tr|h[1-6])[^>]*>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), " ")

        val normalized = decodeHtml(withoutScripts)
            .lines()
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        return ExtractedPage(title = cleanTitle, text = normalized)
    }

    private fun decodeHtml(input: String): String {
        return runCatching {
            Html.fromHtml(input, Html.FROM_HTML_MODE_LEGACY).toString()
        }.getOrElse {
            input.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
        }
    }

    private fun buildDeterministicComparison(
        goal: String,
        criteria: String,
        sources: List<ResearchSourceItem>,
        extractedSnippets: Map<String, String>
    ): String = buildString {
        appendLine("## Comparison & Synthesis")
        if (criteria.isNotBlank()) {
            appendLine("**Criteria:** $criteria")
            appendLine()
        }
        appendLine("| Option / Source | Key Details Retrieved |")
        appendLine("| --- | --- |")
        sources.take(6).forEach { src ->
            val safeTitle = src.title.replace("|", "/").take(60)
            val detail = (extractedSnippets[src.title] ?: src.snippet)
                .replace("|", "/")
                .replace("\n", " ")
                .trim()
                .take(220)
            appendLine("| $safeTitle | ${detail.ifBlank { src.url }} |")
        }
        if (extractedSnippets.isNotEmpty()) {
            appendLine()
            appendLine("### Page Extracts")
            extractedSnippets.entries.take(4).forEach { (title, snippet) ->
                appendLine("- **$title**: ${snippet.replace("\n", " ").take(320)}")
            }
        }
        appendLine()
        appendLine("_Generated strictly from ${sources.size} retrieved source(s) for goal: ${goal.take(120)}._")
    }.trim()

    companion object {
        const val DEFAULT_WIKIPEDIA_BASE_URL = "https://en.wikipedia.org"
        const val DEFAULT_DDG_BASE_URL = "https://api.duckduckgo.com"
        private const val MAX_BODY_CHARS = 120_000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
