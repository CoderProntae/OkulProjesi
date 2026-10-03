package com.example.ai.data

import com.example.ai.model.WebSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebSearchEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    suspend fun searchWeb(query: String): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<WebSearchResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            // Query DuckDuckGo Instant API (JSON)
            val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val heading = json.optString("Heading", "")
                    val abstractText = json.optString("AbstractText", "")
                    val abstractUrl = json.optString("AbstractURL", "")

                    if (abstractText.isNotBlank()) {
                        results.add(
                            WebSearchResult(
                                title = if (heading.isNotBlank()) heading else query,
                                snippet = abstractText,
                                url = abstractUrl
                            )
                        )
                    }

                    val related = json.optJSONArray("RelatedTopics") ?: JSONArray()
                    for (i in 0 until related.length().coerceAtMost(4)) {
                        val item = related.optJSONObject(i)
                        if (item != null) {
                            val text = item.optString("Text", "")
                            val firstUrl = item.optString("FirstURL", "")
                            if (text.isNotBlank()) {
                                results.add(
                                    WebSearchResult(
                                        title = text.take(60),
                                        snippet = text,
                                        url = firstUrl
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Fallback to Wikipedia API if DuckDuckGo gave few results
            if (results.isEmpty()) {
                val wikiUrl = "https://tr.wikipedia.org/w/api.php?action=opensearch&search=$encodedQuery&limit=3&namespace=0&format=json"
                val wikiReq = Request.Builder().url(wikiUrl).build()
                val wikiResp = client.newCall(wikiReq).execute()
                if (wikiResp.isSuccessful) {
                    val wikiBody = wikiResp.body?.string().orEmpty()
                    if (wikiBody.startsWith("[")) {
                        val arr = JSONArray(wikiBody)
                        if (arr.length() >= 4) {
                            val titles = arr.optJSONArray(1) ?: JSONArray()
                            val snippets = arr.optJSONArray(2) ?: JSONArray()
                            val links = arr.optJSONArray(3) ?: JSONArray()

                            for (k in 0 until titles.length()) {
                                val t = titles.optString(k, "")
                                val s = snippets.optString(k, "")
                                val l = links.optString(k, "")
                                if (t.isNotBlank() && s.isNotBlank()) {
                                    results.add(
                                        WebSearchResult(
                                            title = t,
                                            snippet = s,
                                            url = l
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        results
    }
}
