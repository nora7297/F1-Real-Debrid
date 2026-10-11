package com.f1debrid.core.reddit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

class RateLimitedException(val retryAfterMs: Long) :
    IOException("Reddit rate limit hit, anonymous access allows only a few requests per minute; cooling down ${retryAfterMs / 1000}s")

/**
 * Reads a subreddit without needing a Reddit account, trying several routes in order until one works:
 *
 * 1. Reddit's official OAuth API, if the user has entered a client id of their own "installed app"
 *    (https://www.reddit.com/prefs/apps). This is the route Reddit never blocks or throttles hard.
 * 2. The public JSON listing, with the app's own User-Agent and then a browser one.
 * 3. The Atom/RSS feed, which Reddit keeps open for feed readers.
 *
 * Anonymous requests are paced (Reddit allows only a few per minute), a 429 is retried after the
 * delay Reddit asks for, routes that fail hard are skipped for a while, and the route that last
 * succeeded is tried first next time.
 */
class RedditClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val hosts: List<String> = DEFAULT_HOSTS,
    private val clientId: () -> String? = { null },
    private val deviceId: String = UUID.randomUUID().toString().replace("-", "").take(30),
    private val oauthBase: String = "https://oauth.reddit.com",
    private val tokenUrl: String = "https://www.reddit.com/api/v1/access_token",
    private val minIntervalMs: Long = 2500,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RedditSource {

    companion object {
        val DEFAULT_HOSTS = listOf("https://www.reddit.com")
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val INSTALLED_CLIENT_GRANT = "https://oauth.reddit.com/grants/installed_client"
        private const val DEAD_ROUTE_MS = 10 * 60_000L
        private const val MAX_429_RETRIES = 1
        private const val ANON_COOLDOWN_MS = 60_000L
    }

    private enum class Mode { OAUTH, JSON, RSS }
    private data class Attempt(val mode: Mode, val host: String, val ua: String) {
        val label: String
            get() = mode.name.lowercase() + " " + host.removePrefix("https://").removePrefix("http://") +
                if (ua == BROWSER_UA) " (browser)" else ""
    }

    private class HttpResult(val code: Int, val body: String, val retryAfterMs: Long?)

    @Volatile private var preferred: Attempt? = null
    @Volatile private var token: Pair<String, Long>? = null
    private val tokenMutex = Mutex()
    private val deadUntil = HashMap<Attempt, Long>()
    private val throttleMutex = Mutex()
    private var nextAllowedAt = 0L
    @Volatile private var anonymousCooldownUntil = 0L

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
        for (h in hosts) list += Attempt(Mode.RSS, h, userAgent)
        list += Attempt(Mode.RSS, hosts.first(), BROWSER_UA)
        val p = preferred
        val ordered = if (p != null && p in list) listOf(p) + (list - p) else list
        val now = clock()
        val alive = synchronized(deadUntil) { ordered.filter { (deadUntil[it] ?: 0L) <= now } }
        return alive.ifEmpty { ordered }
    }

    private fun markDead(attempt: Attempt, forMs: Long) {
        synchronized(deadUntil) { deadUntil[attempt] = clock() + forMs }
    }

    private suspend fun <T> tryAll(what: String, block: suspend (Attempt) -> T): T {
        val errors = ArrayList<String>()
        for (attempt in attempts()) {
            try {
                val result = block(attempt)
                preferred = attempt
                return result
            } catch (e: RateLimitedException) {
                // Every anonymous route shares the same budget, so trying the others is pointless.
                errors += "${attempt.label}: ${e.message}"
                break
            } catch (e: Exception) {
                markDead(attempt, DEAD_ROUTE_MS)
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
            val result = execute(req)
            if (result.code in 200..299) return result.body
            if (result.code == 401 && attempt == 0) {
                token = null
                bearer = accessToken()
            } else {
                throw IOException("HTTP ${result.code} ${snippet(result.body)}")
            }
        }
        throw IOException("unauthorised")
    }

    private suspend fun accessToken(): String = tokenMutex.withLock {
        token?.let { (t, expiresAt) -> if (expiresAt - clock() > 60_000) return@withLock t }
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
        val result = execute(req)
        if (result.code !in 200..299) throw IOException("token request HTTP ${result.code} ${snippet(result.body)}")
        val obj = runCatching { RedditJson.json.parseToJsonElement(result.body).jsonObject }.getOrNull()
            ?: throw IOException("token reply was not JSON")
        val t = obj["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IOException("token reply had no access_token ${snippet(result.body)}")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        token = t to (clock() + expiresIn * 1000)
        t
    }

    /**
     * Anonymous GET: paced; a 429 with a short Retry-After is retried once, any other 429 puts all
     * anonymous routes into a cool-down so the rest of the sync does not burn the remaining budget.
     */
    private suspend fun get(url: String, ua: String, accept: String): String {
        val remaining = anonymousCooldownUntil - clock()
        if (remaining > 0) throw RateLimitedException(remaining)
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept", accept).build()
        var tries = 0
        while (true) {
            tries++
            val result = throttled { execute(req) }
            if (result.code in 200..299) return result.body
            if (result.code == 429) {
                val retryAfter = result.retryAfterMs
                if (retryAfter != null && retryAfter <= 30_000L && tries <= MAX_429_RETRIES) {
                    sleep(retryAfter.coerceAtLeast(1_000L))
                    continue
                }
                val cooldown = retryAfter?.coerceIn(5_000L, 10 * 60_000L) ?: ANON_COOLDOWN_MS
                anonymousCooldownUntil = clock() + cooldown
                throw RateLimitedException(cooldown)
            }
            throw IOException("HTTP ${result.code} ${snippet(result.body)}")
        }
    }

    private suspend fun <T> throttled(block: suspend () -> T): T = throttleMutex.withLock {
        val wait = nextAllowedAt - clock()
        if (wait > 0) sleep(wait)
        try {
            block()
        } finally {
            nextAllowedAt = clock() + minIntervalMs
        }
    }

    private suspend fun execute(req: Request): HttpResult = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
            HttpResult(resp.code, resp.body?.string().orEmpty(), retryAfter)
        }
    }

    private fun snippet(body: String): String {
        val text = RedditRss.htmlToText(body).replace(Regex("""\s+"""), " ").trim()
        return if (text.isEmpty()) "" else "[" + text.take(60) + "]"
    }
}
