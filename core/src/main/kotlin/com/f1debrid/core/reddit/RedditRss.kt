package com.f1debrid.core.reddit

import java.io.IOException
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Reads Reddit's Atom feeds (https://www.reddit.com/r/<sub>/new/.rss). Reddit keeps these open even
 * when it blocks anonymous JSON requests, so they are the fallback when the JSON listing fails.
 */
object RedditRss {
    private val ENTRY = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
    private val LINK = Regex("""<link[^>]*href="([^"]+)"""")
    private val ANCHOR = Regex("""<a\s[^>]*href="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("""<br\s*/?>|</p>|</li>|</div>|</h\d>|</tr>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val INVISIBLE = Regex("""<(style|script|head)\b[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|lt|gt|quot|apos|amp|nbsp);""")
    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?""")

    fun parseListing(xml: String): RedditPage {
        if (!xml.contains("<feed") && !xml.contains("<entry")) throw IOException("not an Atom feed")
        val posts = ENTRY.findAll(xml).mapNotNull { entryToPost(it.groupValues[1]) }.toList()
        val after = posts.lastOrNull()?.let { "t3_" + it.id }
        return RedditPage(posts, after)
    }

    fun extractCommentBodies(xml: String): List<String> =
        ENTRY.findAll(xml).mapNotNull { m ->
            val entry = m.groupValues[1]
            val id = tag(entry, "id")?.trim() ?: return@mapNotNull null
            if (!id.startsWith("t1_")) return@mapNotNull null
            htmlToText(unescape(tag(entry, "content") ?: "")).ifBlank { null }
        }.toList()

    private fun entryToPost(entry: String): RedditPost? {
        val id = tag(entry, "id")?.trim() ?: return null
        if (!id.startsWith("t3_")) return null
        val title = unescape(tag(entry, "title") ?: "").trim()
        val link = LINK.find(entry)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
        val permalink = link.substringAfter("://", "").substringAfter('/', "").let { if (it.isBlank()) "" else "/$it" }
        val text = htmlToText(unescape(tag(entry, "content") ?: ""))
        val author = tag(entry, "name")?.trim()?.removePrefix("/u/")?.removePrefix("u/")
        val published = tag(entry, "published") ?: tag(entry, "updated")
        val created = published?.let { parseIso(it) } ?: 0L
        return RedditPost(
            id = id.removePrefix("t3_"),
            title = title,
            selftext = text,
            url = link,
            author = author,
            createdUtc = created / 1000.0,
            permalink = permalink,
            numComments = -1,
        )
    }

    private fun tag(entry: String, name: String): String? =
        Regex("""<$name(?:\s[^>]*)?>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1)

    /** Turns rendered post HTML into plain text, keeping link targets so magnet hrefs survive. */
    fun htmlToText(html: String): String {
        var s = INVISIBLE.replace(html, " ")
        s = ANCHOR.replace(s) { m -> " " + m.groupValues[1] + " " }
        s = BLOCK_END.replace(s, "\n")
        s = TAG.replace(s, " ")
        return unescape(s).lines().joinToString("\n") { it.trim() }.trim()
    }

    fun unescape(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            }
        }
    }

    fun parseIso(s: String): Long? {
        val m = ISO.find(s) ?: return null
        val (y, mo, d, h, mi, sec) = m.destructured
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), sec.toInt())
        var millis = cal.timeInMillis
        val zone = m.groupValues[7]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val offsetMin = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
            millis -= sign * offsetMin * 60_000L
        }
        return millis
    }
}
