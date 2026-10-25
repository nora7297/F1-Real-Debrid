package com.f1debrid.core

import com.f1debrid.core.magnet.Magnets
import com.f1debrid.core.model.Quality
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.parse.F1TitleParser
import com.f1debrid.core.reddit.RedditRss
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors a real comment in a r/MotorsportsReplays session thread: one comment with a bold release
 * name, size and notes per quality, and the magnet as plain text in a quote block (not a link).
 * Hashes are made up.
 */
class SessionThreadCommentTest {
    private val h720 = "1111111111111111111111111111111111111111"
    private val h1080 = "2222222222222222222222222222222222222222"
    private val h2160 = "3333333333333333333333333333333333333333"

    private fun block(hash: String, res: String, size: String, notes: String = "") = """
        &lt;p&gt;&lt;strong&gt;Formula.1.2026x98.United.States.Qualifying.F1TV.$res.SS&lt;/strong&gt;&lt;br/&gt;
        Size: $size&lt;/p&gt;
        $notes
        &lt;blockquote&gt;
        &lt;p&gt;magnet:?xt=urn:btih:${hash.uppercase()}&amp;amp;dn=Formula.1.2026x98.United.States.Qualifying.F1TV.$res.SS&amp;amp;tr=udp://tracker.opentrackr.org:1337&amp;amp;tr=udp://open.stealth.si:80/announce&amp;amp;tr=udp://tracker.torrent.eu.org:451/announce&lt;/p&gt;
        &lt;/blockquote&gt;
    """.trimIndent()

    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><title>[21:00 UTC] 2026 F1 - United States Grand Prix - Qualifying</title>
<entry><author><name>/u/automod</name></author><content type="html">&lt;div class="md"&gt;&lt;p&gt;Session thread&lt;/p&gt;&lt;/div&gt;</content><id>t3_q1</id><title>[21:00 UTC] 2026 F1 - United States Grand Prix - Qualifying</title></entry>
<entry><author><name>/u/lonewolf_sg</name></author><content type="html">&lt;!-- SC_OFF --&gt;&lt;div class="md"&gt;${block(h720, "720p", "2.19 GB",
        "&lt;p&gt;Stream: F1 Live - Dedicated channel produced by Formula 1&lt;br/&gt;\nLanguage: English, Spanish (Spanish is now part of the stream)&lt;/p&gt;")}
${block(h1080, "1080p", "3.54 GB")}
${block(h2160, "2160p", "8.97 GB")}&lt;/div&gt;&lt;!-- SC_ON --&gt;</content><id>t1_c1</id><title>/u/lonewolf_sg on [21:00 UTC] 2026 F1 - United States Grand Prix - Qualifying</title></entry>
<entry><author><name>/u/fan</name></author><content type="html">&lt;div class="md"&gt;&lt;p&gt;Goat 🔥🔥 thanks&lt;/p&gt;&lt;/div&gt;</content><id>t1_c2</id><title>/u/fan on thread</title></entry>
</feed>"""

    @Test
    fun allThreeQualitiesAreFoundWithTheirNames() {
        val comments = RedditRss.extractComments(feed)
        assertEquals(listOf("lonewolf_sg", "fan"), comments.map { it.author })
        val magnets = Magnets.extract(comments[0].body)
        assertEquals(listOf(h720, h1080, h2160), magnets.map { it.infoHash })
        val parsed = magnets.map { F1TitleParser.parse(it.displayName!!) }
        assertEquals(listOf(Quality.HD_720, Quality.FHD_1080, Quality.UHD_2160), parsed.map { it.quality })
        assertTrue(parsed.all { it.sessions == listOf(SessionType.QUALIFYING) })
        assertTrue(parsed.all { it.broadcaster == "F1TV" })
        assertTrue(magnets.all { it.raw.contains("&tr=udp://tracker.opentrackr.org:1337") }, magnets[0].raw)
    }

    @Test
    fun threadTitleGivesTheEvent() {
        val p = F1TitleParser.parse("[21:00 UTC] 2026 F1 - United States Grand Prix - Qualifying")
        assertTrue(p.isF1)
        assertEquals(2026, p.year)
        assertEquals("United States", p.gpName)
        assertEquals(listOf(SessionType.QUALIFYING), p.sessions)
        assertEquals("2026-united-states", F1TitleParser.eventKey(p, "x"))
    }
}
