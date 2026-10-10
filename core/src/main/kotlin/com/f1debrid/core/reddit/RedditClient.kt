package com.f1debrid.core.reddit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID

interface RedditSource {
    suspend fun fetchNew(subreddit: String, limit: Int = 100, after: String? = null): RedditPage
    suspend fun fetchCommentBodies(permalink: String): List<String>
}

/**
 * Reads a subreddit without needing a Reddit account, trying several routes in order until one works:
 *
 * 1. Reddit's official OAuth API, if the user has entered a client id of their own "installed app"
 *    (https://www.reddit.com/prefs/apps). This is the route Reddit never blocks.
 * 2. The public JSON listing on a few hosts, with the app's own User-Agent and then a browser one.
 * 3. The Atom/RSS feed, which Reddit keeps open for feed readers.
 *
 * The route that last succeeded is tried first next time.
 */
class RedditClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val hosts: List<String> = DEFAULT_HOSTS,
    private val clientId: () -> String? = { null },
    private val deviceId: String = UUID.randomUUID().toString().replace("-", "").take(30),
    private val oauthBase: String = "https://oauth.reddit.com",
    private val tokenUrl: String = "https://www.reddit.com/api/v1/access_token",
) : RedditSource {

    companion object {
        val DEFAULT_HOSTS = listOf("https://www.reddit.com", "https://old.reddit.com", "https://api.reddit.com")
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val INSTALLED_CLIENT_GRANT = "https://oauth.reddit.com/grants/installed_client"
    }

    private enum class Mode { OAUTH, JSON, RSS }
    private data class Attempt(val mode: Mode, val host: String, val ua: String) {
        val label: String get() = mode.name.lowercase() + " " + host.removePrefix("https://").removePrefix("http://")
    }

    @Volatile private var preferred: Attempt? = null
    @Volatile private var token: Pair<String, Long>? = null
    private val tokenMutex = Mutex()

    override suspend fun fetchNew(subreddit: String, limit: Int, after: String?): RedditPage {
        val lim = limit.coerceIn(1, 100)
        val afterParam = if (after.isNullOrBlank()) "" else "&after=$after"
        return tryAll("listing") { a ->
            when (a.mode) {
                Mode.OAUTH -> RedditJson.parseListing(getOauth("$oauthBase/r/$subreddit/new?limit=$lim&raw_json=1$afterParam"))
                Mode.JSON -> RedditJson.parseListing(getJson("${a.host}/r/$subreddit/new.json?limit=$lim&raw_json=1$afterParam", a.ua))
                Mode.RSS -> RedditRss.parseListing(getFeed("${a.host}/r/$subreddit/new/.rss?limit=$lim$afterParam", a.ua))
            }
        }
    }

    override suspend fun fetchCommentBodies(permalink: String): List<String> {
        val path = "/" + permalink.trim('/')
        return tryAll("comments") { a ->
            when (a.mode) {
                Mode.OAUTH -> RedditJson.extractCommentBodies(getOauth("$oauthBase$path?raw_json=1&limit=100&depth=2&sort=top"))
                Mode.JSON -> RedditJson.extractCommentBodies(getJson("${a.host}$path.json?raw_json=1&limit=100&depth=2&sort=top", a.ua))
                Mode.RSS -> RedditRss.extractCommentBodies(getFeed("${a.host}$path/.rss?limit=100", a.ua))
            }
        }
    }

    private fun attempts(): List<Attempt> {
        val list = ArrayList<Attempt>()
        if (!clientId().isNullOrBlank()) list += Attempt(Mode.OAUTH, oauthBase, userAgent)
        for (h in hosts) list += Attempt(Mode.JSON, h, userAgent)
        list += Attempt(Mode.JSON, hosts.first(), BROWSER_UA)
        for (h in hosts.take(2)) list += Attempt(Mode.RSS, h, userAgent)
        list += Attempt(Mode.RSS, hosts.first(), BROWSER_UA)
        val p = preferred
        return if (p != null && p in list) listOf(p) + (list - p) else list
    }

    private suspend fun <T> tryAll(what: String, block: suspend (Attempt) -> T): T {
        val errors = ArrayList<String>()
        for (attempt in attempts()) {
            try {
                val result = block(attempt)
                preferred = attempt
                return result
            } catch (e: Exception) {
                errors += "${attempt.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException("Reddit $what failed (" + errors.joinToString("; ") + ")")
    }

    private suspend fun getJson(url: String, ua: String): String {
        val body = get(url, ua, "application/json")
        val head = body.trimStart()
        if (!head.startsWith("{") && !head.startsWith("[")) throw IOException("non-JSON reply ${snippet(body)}")
        return body
    }

    private suspend fun getFeed(url: String, ua: String): String {
        val body = get(url, ua, "application/atom+xml, application/xml, text/xml, */*")
        if (!body.contains("<feed") && !body.contains("<entry")) throw IOException("not a feed ${snippet(body)}")
        return body
    }

    private suspend fun getOauth(url: String): String {
        var bearer = accessToken()
        repeat(2) { attempt ->
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $bearer")
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()
            val (code, body) = execute(req)
            if (code in 200..299) return body
            if (code == 401 && attempt == 0) {
                token = null
                bearer = accessToken()
            } else {
                throw IOException("HTTP $code ${snippet(body)}")
            }
        }
        throw IOException("unauthorised")
    }

    private suspend fun accessToken(): String = tokenMutex.withLock {
        token?.let { (t, expiresAt) -> if (expiresAt - System.currentTimeMillis() > 60_000) return@withLock t }
        val id = clientId()?.trim()?.takeIf { it.isNotEmpty() } ?: throw IOException("no Reddit client id")
        val form = FormBody.Builder()
            .add("grant_type", INSTALLED_CLIENT_GRANT)
            .add("device_id", deviceId.padEnd(20, '0').take(30))
            .build()
        val req = Request.Builder().url(tokenUrl)
            .header("Authorization", Credentials.basic(id, ""))
            .header("User-Agent", userAgent)
            .post(form)
            .build()
        val (code, body) = execute(req)
        if (code !in 200..299) throw IOException("token request HTTP $code ${snippet(body)}")
        val obj = runCatching { RedditJson.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw IOException("token reply was not JSON")
        val t = obj["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IOException("token reply had no access_token ${snippet(body)}")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        token = t to (System.currentTimeMillis() + expiresIn * 1000)
        t
    }

    private suspend fun get(url: String, ua: String, accept: String): String {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept", accept).build()
        val (code, body) = execute(req)
        if (code !in 200..299) throw IOException("HTTP $code ${snippet(body)}")
        return body
    }

    private suspend fun execute(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp -> resp.code to (resp.body?.string().orEmpty()) }
    }

    private fun snippet(body: String): String {
        val text = RedditRss.htmlToText(body).replace(Regex("""\s+"""), " ").trim()
        return if (text.isEmpty()) "" else "[" + text.take(70) + "]"
    }
}
