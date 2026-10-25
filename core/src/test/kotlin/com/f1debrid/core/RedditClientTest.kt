package com.f1debrid.core

import com.f1debrid.core.reddit.RedditClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RedditClientTest {
    private val blocked = MockResponse().setResponseCode(403).setBody("<html><body><h1>Blocked</h1><p>network security</p></body></html>")

    private val listingJson = """{"kind":"Listing","data":{"after":null,"children":[{"kind":"t3","data":{"id":"x1",
        "title":"F1 2026 Qatar GP Race Sky 2160p","selftext":"magnet:?xt=urn:btih:${RssFixtures.HASH}",
        "created_utc":1760000000.0,"permalink":"/r/MotorsportsReplays/comments/x1/a/","num_comments":0}}]}}"""

    private fun withServer(dispatcher: (RecordedRequest) -> MockResponse, block: suspend (MockWebServer, String) -> Unit) = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = dispatcher(request)
        }
        server.start()
        try {
            block(server, server.url("/").toString().trimEnd('/'))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fallsBackToRssWhenJsonIsBlocked() = withServer({ req ->
        val path = req.path.orEmpty()
        when {
            path.contains(".json") -> blocked
            path.contains("/comments/") && path.contains(".rss") -> MockResponse().setBody(RssFixtures.comments)
            path.contains(".rss") -> MockResponse().setBody(RssFixtures.listing)
            else -> MockResponse().setResponseCode(404)
        }
    }) { server, base ->
        val client = RedditClient(OkHttpClient(), "test-agent", hosts = listOf("$base/www", "$base/old", "$base/api"))
        val page = client.fetchNew("MotorsportsReplays", 100, null)
        assertEquals(2, page.posts.size)
        assertEquals("1abcde", page.posts[0].id)
        // 3 hosts with the app UA + 1 browser-UA attempt fail, then the first RSS attempt succeeds.
        assertEquals(5, server.requestCount)

        val bodies = client.fetchCommentBodies(page.posts[0].permalink)
        assertEquals(2, bodies.size)
        // RSS is now the preferred route, so the comments took a single request.
        assertEquals(6, server.requestCount)
    }

    @Test
    fun reportsEveryRouteWhenAllFail() = withServer({ blocked }) { _, base ->
        val client = RedditClient(OkHttpClient(), "test-agent", hosts = listOf("$base/www", "$base/old"))
        val ex = assertFailsWith<IOException> { client.fetchNew("MotorsportsReplays", 100, null) }
        val msg = ex.message.orEmpty()
        assertTrue(msg.contains("HTTP 403"), msg)
        assertTrue(msg.contains("Blocked"), msg)
        assertTrue(msg.contains("rss"), msg)
    }

    @Test
    fun usesOfficialApiWhenClientIdIsSet() = withServer({ req ->
        val path = req.path.orEmpty()
        when {
            path.startsWith("/api/v1/access_token") -> MockResponse().setBody("""{"access_token":"tok123","token_type":"bearer","expires_in":3600,"scope":"*"}""")
            path.startsWith("/oauth/r/") && req.getHeader("Authorization") == "Bearer tok123" -> MockResponse().setBody(listingJson)
            else -> blocked
        }
    }) { server, base ->
        val client = RedditClient(
            OkHttpClient(), "test-agent", hosts = listOf("$base/www"),
            clientId = { "myclientid" }, deviceId = "device1234567890abcdef",
            oauthBase = "$base/oauth", tokenUrl = "$base/api/v1/access_token",
        )
        val page = client.fetchNew("MotorsportsReplays", 100, null)
        assertEquals(1, page.posts.size)
        assertEquals("x1", page.posts[0].id)
        assertEquals(2, server.requestCount)
        val tokenRequest = server.takeRequest()
        assertEquals("Basic bXljbGllbnRpZDo=", tokenRequest.getHeader("Authorization"))
        assertTrue(tokenRequest.body.readUtf8().contains("grant_type=https%3A%2F%2Foauth.reddit.com%2Fgrants%2Finstalled_client"))
    }
}

class RedditClientPacingTest {
    private val feed = RssFixtures.listing

    @Test
    fun retriesAfter429AndSkipsDeadRoutes() {
        kotlinx.coroutines.test.runTest {
            val server = MockWebServer()
            var rssCalls = 0
            var jsonCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.contains(".json") -> { jsonCalls++; MockResponse().setResponseCode(403).setBody("<html><head><style>.x{}</style></head><body>Blocked</body></html>") }
                        path.contains(".rss") -> {
                            rssCalls++
                            if (rssCalls == 1) MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("too many")
                            else MockResponse().setBody(feed)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            try {
                val sleeps = ArrayList<Long>()
                var now = 1_000_000L
                val base = server.url("/").toString().trimEnd('/')
                val client = RedditClient(
                    OkHttpClient(), "test-agent", hosts = listOf("$base/www"),
                    minIntervalMs = 2500, sleep = { ms -> sleeps += ms; now += ms }, clock = { now },
                )
                val page = client.fetchNew("MotorsportsReplays", 100, null)
                assertEquals(2, page.posts.size)
                // JSON (app UA + browser UA) failed hard, RSS got a 429 once and was retried after Retry-After.
                assertEquals(2, jsonCalls)
                assertEquals(2, rssCalls)
                assertTrue(sleeps.contains(1000L), sleeps.toString())

                // Second call: JSON routes are dead for a while, RSS is preferred -> exactly one more RSS request.
                client.fetchNew("MotorsportsReplays", 100, null)
                assertEquals(2, jsonCalls)
                assertEquals(3, rssCalls)
                // Requests are paced at least minIntervalMs apart.
                assertTrue(sleeps.any { it in 1L..2500L }, sleeps.toString())
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun errorSnippetsIgnoreStylesheets() {
        val text = com.f1debrid.core.reddit.RedditRss.htmlToText("<html><head><style>.theme-light{--x:1}</style><script>var a=1;</script></head><body><h1>Blocked</h1></body></html>")
        assertEquals("Blocked", text)
    }
}


class RedditClientCooldownTest {
    @Test
    fun a429WithoutRetryAfterStartsACooldown() {
        kotlinx.coroutines.test.runTest {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(429).setBody("<html><body>Too many requests</body></html>")
            }
            server.start()
            try {
                var now = 5_000_000L
                val base = server.url("/").toString().trimEnd('/')
                val client = RedditClient(
                    OkHttpClient(), "test-agent", hosts = listOf("$base/www"),
                    minIntervalMs = 0, sleep = { ms -> now += ms }, clock = { now },
                )
                val first = assertFailsWith<IOException> { client.fetchNew("MotorsportsReplays", 100, null) }
                assertTrue(first.message.orEmpty().contains("rate limit"), first.message)
                assertTrue(first is com.f1debrid.core.reddit.RateLimitedException, "rate limit must keep its type for the sync to wait it out")
                // Only one route was tried: the rest were skipped once the budget was known to be gone.
                assertEquals(1, server.requestCount)

                // Inside the cool-down nothing touches the network.
                assertFailsWith<IOException> { client.fetchCommentBodies("/r/x/comments/y/z/") }
                assertEquals(1, server.requestCount)

                // After the cool-down requests flow again.
                now += 61_000
                assertFailsWith<IOException> { client.fetchNew("MotorsportsReplays", 100, null) }
                assertEquals(2, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }
}
