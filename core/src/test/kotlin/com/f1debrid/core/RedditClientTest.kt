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
