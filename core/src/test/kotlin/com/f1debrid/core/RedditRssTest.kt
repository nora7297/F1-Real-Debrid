package com.f1debrid.core

import com.f1debrid.core.magnet.Magnets
import com.f1debrid.core.reddit.RedditRss
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

object RssFixtures {
    const val HASH = "0123456789abcdef0123456789abcdef01234567"

    val listing = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><category term="MotorsportsReplays" label="r/MotorsportsReplays"/><updated>2026-10-10T12:00:00+00:00</updated><id>/r/MotorsportsReplays/new/.rss?limit=100</id><title>newest submissions : MotorsportsReplays</title>
<entry><author><name>/u/poster</name><uri>https://old.reddit.com/user/poster</uri></author><category term="MotorsportsReplays" label="r/MotorsportsReplays"/><content type="html">&lt;!-- SC_OFF --&gt;&lt;div class="md"&gt;&lt;p&gt;&lt;strong&gt;Race&lt;/strong&gt;&lt;/p&gt;

&lt;p&gt;&lt;a href="magnet:?xt=urn:btih:$HASH&amp;amp;dn=F1.2026.R19.Race.2160p&amp;amp;tr=udp%3A%2F%2Ft.example"&gt;magnet&lt;/a&gt;&lt;/p&gt;
&lt;/div&gt;&lt;!-- SC_ON --&gt; &amp;#32; submitted by &amp;#32; &lt;a href="https://old.reddit.com/user/poster"&gt; /u/poster &lt;/a&gt;</content><id>t3_1abcde</id><link href="https://old.reddit.com/r/MotorsportsReplays/comments/1abcde/formula_1_2026_round_19/" /><updated>2026-10-10T11:30:00+00:00</updated><published>2026-10-10T11:30:00+00:00</published><title>Formula 1 2026 Round 19 United States Grand Prix Race Sky Sports F1 UHD 2160p</title></entry>
<entry><author><name>/u/other</name></author><content type="html">&lt;div&gt;no links&lt;/div&gt;</content><id>t3_1fghij</id><link href="https://old.reddit.com/r/MotorsportsReplays/comments/1fghij/motogp/" /><updated>2026-10-10T10:00:00+02:00</updated><title>MotoGP 2026 Round 15 Race &amp; Sprint 1080p</title></entry>
</feed>"""

    val comments = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><title>thread</title>
<entry><author><name>/u/poster</name></author><content type="html">&lt;div&gt;post body&lt;/div&gt;</content><id>t3_1abcde</id><title>Formula 1 2026 Round 19</title></entry>
<entry><author><name>/u/helper</name></author><content type="html">&lt;div class="md"&gt;&lt;p&gt;here: &lt;a href="magnet:?xt=urn:btih:$HASH&amp;amp;dn=Quali"&gt;link&lt;/a&gt;&lt;/p&gt;&lt;/div&gt;</content><id>t1_c1</id><title>/u/helper on thread</title></entry>
<entry><author><name>/u/other</name></author><content type="html">&lt;div class="md"&gt;&lt;p&gt;thanks!&lt;/p&gt;&lt;/div&gt;</content><id>t1_c2</id><title>/u/other on thread</title></entry>
</feed>"""
}

class RedditRssTest {
    @Test
    fun parsesEntries() {
        val page = RedditRss.parseListing(RssFixtures.listing)
        assertEquals(2, page.posts.size)
        val first = page.posts[0]
        assertEquals("1abcde", first.id)
        assertEquals("Formula 1 2026 Round 19 United States Grand Prix Race Sky Sports F1 UHD 2160p", first.title)
        assertEquals("/r/MotorsportsReplays/comments/1abcde/formula_1_2026_round_19/", first.permalink)
        assertEquals("poster", first.author)
        assertEquals(-1, first.numComments)
        assertEquals(RedditRss.parseIso("2026-10-10T11:30:00+00:00"), first.createdMillis)
        val magnets = Magnets.extract(first.selftext)
        assertEquals(1, magnets.size)
        assertEquals(RssFixtures.HASH, magnets[0].infoHash)
        assertEquals("F1.2026.R19.Race.2160p", magnets[0].displayName)
        assertEquals("MotoGP 2026 Round 15 Race & Sprint 1080p", page.posts[1].title)
        assertEquals("t3_1fghij", page.after)
    }

    @Test
    fun parsesTimestamps() {
        assertEquals(0L, RedditRss.parseIso("1970-01-01T00:00:00Z"))
        assertEquals(0L, RedditRss.parseIso("1970-01-01T01:00:00+01:00"))
        assertEquals(RedditRss.parseIso("2026-10-10T11:30:00+00:00"), RedditRss.parseIso("2026-10-10T13:30:00+02:00"))
        assertEquals(3_600_000L, RedditRss.parseIso("1970-01-01T00:00:00-01:00"))
    }

    @Test
    fun commentsFeedSkipsThePostItself() {
        val bodies = RedditRss.extractCommentBodies(RssFixtures.comments)
        assertEquals(2, bodies.size)
        assertEquals(RssFixtures.HASH, Magnets.extract(bodies[0]).single().infoHash)
        assertEquals("thanks!", bodies[1])
    }

    @Test
    fun rejectsNonFeed() {
        assertFailsWith<IOException> { RedditRss.parseListing("<html><body>Blocked</body></html>") }
    }

    @Test
    fun unescapesEntities() {
        assertEquals("a & b < c \"d\" ' ", RedditRss.unescape("a &amp; b &lt; c &quot;d&quot; &#39;&#x20;"))
    }
}
