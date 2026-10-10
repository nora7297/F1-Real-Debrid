package com.f1debrid.core.reddit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

interface RedditSource {
    suspend fun fetchNew(subreddit: String, limit: Int = 100, after: String? = null): RedditPage
    suspend fun fetchCommentBodies(permalink: String): List<String>
}

/**
 * Reads the public JSON listing of a subreddit. No Reddit account is required; Reddit only asks for a
 * descriptive User-Agent. Several hosts are tried in order in case one of them is rate-limiting.
 */
class RedditClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val hosts: List<String> = DEFAULT_HOSTS,
) : RedditSource {

    companion object {
        val DEFAULT_HOSTS = listOf("https://www.reddit.com", "https://old.reddit.com", "https://api.reddit.com")
    }

    override suspend fun fetchNew(subreddit: String, limit: Int, after: String?): RedditPage {
        val path = buildString {
            append("/r/").append(subreddit).append("/new.json?limit=").append(limit.coerceIn(1, 100)).append("&raw_json=1")
            if (!after.isNullOrBlank()) append("&after=").append(after)
        }
        return get(path) { RedditJson.parseListing(it) }
    }

    override suspend fun fetchCommentBodies(permalink: String): List<String> {
        val path = permalink.trimEnd('/') + ".json?raw_json=1&limit=100&depth=2&sort=top"
        return get(path) { RedditJson.extractCommentBodies(it) }
    }

    private suspend fun <T> get(path: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (host in hosts) {
            try {
                val request = Request.Builder()
                    .url(host + path)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()
                val result = http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IOException("Reddit returned HTTP ${resp.code} from $host")
                    val head = body.trimStart()
                    if (!head.startsWith("{") && !head.startsWith("[")) throw IOException("Reddit returned a non-JSON page from $host")
                    parse(body)
                }
                return@withContext result
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Reddit is unreachable")
    }
}
