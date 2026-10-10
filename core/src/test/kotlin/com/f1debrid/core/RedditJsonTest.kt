package com.f1debrid.core

import com.f1debrid.core.reddit.RedditJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedditJsonTest {
    private val listing = """
    {"kind": "Listing", "data": {"after": "t3_zzz", "dist": 2, "children": [
      {"kind": "t3", "data": {"id": "abc123", "name": "t3_abc123", "title": "Formula 1 2026 Round 19 United States Grand Prix Race Sky Sports F1 UHD 2160p",
        "selftext": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=F1.Race", "url": "https://www.reddit.com/r/MotorsportsReplays/comments/abc123/x/",
        "link_flair_text": "Formula 1", "author": "someone", "created_utc": 1760000000.0, "permalink": "/r/MotorsportsReplays/comments/abc123/x/", "num_comments": 4, "edited": 1760000500.0, "stickied": false}},
      {"kind": "t3", "data": {"id": "def456", "title": "MotoGP 2026 Round 15 Race 1080p", "selftext": "", "created_utc": 1759990000, "permalink": "/r/MotorsportsReplays/comments/def456/y/", "link_flair_text": null}},
      {"kind": "t5", "data": {"id": "ignored"}}
    ]}}
    """.trimIndent()

    private val comments = """
    [
      {"kind": "Listing", "data": {"children": [{"kind": "t3", "data": {"id": "abc123", "selftext": "post body"}}]}},
      {"kind": "Listing", "data": {"children": [
        {"kind": "t1", "data": {"body": "first comment magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", "replies": ""}},
        {"kind": "t1", "data": {"body": "second", "replies": {"kind": "Listing", "data": {"children": [
            {"kind": "t1", "data": {"body": "nested reply", "replies": ""}},
            {"kind": "more", "data": {"children": ["x"]}}
        ]}}}}
      ]}}
    ]
    """.trimIndent()

    @Test
    fun parsesListing() {
        val page = RedditJson.parseListing(listing)
        assertEquals("t3_zzz", page.after)
        assertEquals(2, page.posts.size)
        val first = page.posts[0]
        assertEquals("abc123", first.id)
        assertEquals("Formula 1", first.linkFlairText)
        assertEquals(1760000000000L, first.createdMillis)
        assertEquals(4, first.numComments)
        assertEquals("https://www.reddit.com/r/MotorsportsReplays/comments/abc123/x/", first.fullUrl)
        assertNull(page.posts[1].linkFlairText)
    }

    @Test
    fun walksComments() {
        val bodies = RedditJson.extractCommentBodies(comments)
        assertEquals(listOf("first comment magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", "second", "nested reply"), bodies)
    }

    @Test
    fun toleratesRubbish() {
        assertTrue(RedditJson.parseListing("<html>blocked</html>").posts.isEmpty())
        assertTrue(RedditJson.extractCommentBodies("nope").isEmpty())
    }
}
