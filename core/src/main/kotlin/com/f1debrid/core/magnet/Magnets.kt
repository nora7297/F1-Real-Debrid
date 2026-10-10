package com.f1debrid.core.magnet

import java.net.URLDecoder

/**
 * A magnet link found in some text.
 *
 * @property raw the cleaned magnet URI as it should be sent to Real-Debrid
 * @property infoHash lowercase hex info-hash (40 chars), used for de-duplication
 * @property displayName the `dn` parameter, if present
 * @property contextLabel the nearest label/heading that preceded the link in the text
 */
data class MagnetLink(
    val raw: String,
    val infoHash: String,
    val displayName: String?,
    val contextLabel: String?,
)

object Magnets {
    private val MAGNET = Regex("""magnet:\?[^\s"'<>()\[\]]+""", RegexOption.IGNORE_CASE)
    private val BTIH = Regex("""urn:btih:([A-Fa-f0-9]{40}|[A-Za-z2-7]{32})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val MD_LINK_LABEL = Regex("""\[([^\]]{1,200})\]\(\s*$""")
    private val LABEL_NOISE = Regex("""[*_#>`\[\]()|:\-–—]+""")
    private val WS = Regex("""\s+""")

    /** Undo the HTML-entity / markdown escaping Reddit applies to post bodies. */
    fun clean(text: String): String = text
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&#x26;", "&")
        .replace("\\", "")

    /** All distinct magnet links in [text], in order of appearance. */
    fun extract(text: String?): List<MagnetLink> {
        if (text.isNullOrBlank()) return emptyList()
        val cleaned = clean(text)
        val out = LinkedHashMap<String, MagnetLink>()
        for (m in MAGNET.findAll(cleaned)) {
            val raw = m.value.trimEnd('.', ',', ';', '*', '_', '!')
            val link = parse(raw) ?: continue
            if (out.containsKey(link.infoHash)) continue
            out[link.infoHash] = link.copy(contextLabel = contextLabel(cleaned, m.range.first))
        }
        return out.values.toList()
    }

    fun parse(raw: String): MagnetLink? {
        val hashMatch = BTIH.find(raw) ?: return null
        val hash = normalizeHash(hashMatch.groupValues[1]) ?: return null
        return MagnetLink(raw, hash, param(raw, "dn"), null)
    }

    /** Accepts a 40-char hex or 32-char base32 info-hash and returns lowercase hex. */
    fun normalizeHash(h: String): String? = when (h.length) {
        40 -> if (h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) h.lowercase() else null
        32 -> base32ToHex(h)
        else -> null
    }

    private fun param(raw: String, name: String): String? {
        val query = raw.substringAfter('?', "")
        for (part in query.split('&')) {
            val key = part.substringBefore('=')
            if (!key.equals(name, ignoreCase = true)) continue
            val value = part.substringAfter('=', "")
            val decoded = runCatching { URLDecoder.decode(value.replace("+", "%20"), "UTF-8") }.getOrDefault(value)
            return decoded.trim().ifBlank { null }
        }
        return null
    }

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32ToHex(s: String): String? {
        var bits = 0
        var value = 0L
        val bytes = ArrayList<Int>(20)
        for (c in s.uppercase()) {
            val idx = B32.indexOf(c)
            if (idx < 0) return null
            value = (value shl 5) or idx.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes.add(((value shr bits) and 0xFF).toInt())
                value = value and ((1L shl bits) - 1)
            }
        }
        if (bytes.size != 20) return null
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun contextLabel(text: String, start: Int): String? {
        val before = text.substring(0, start)
        MD_LINK_LABEL.find(before)?.let { return tidy(it.groupValues[1]).ifBlank { null } }
        val lines = before.split('\n')
        val sameLine = tidy(lines.last())
        if (sameLine.length >= 3) return sameLine
        for (i in lines.size - 2 downTo 0) {
            val line = lines[i]
            if (line.contains("magnet:", ignoreCase = true)) continue
            val t = tidy(line)
            if (t.length >= 2) return t
        }
        return null
    }

    private fun tidy(s: String): String = s.replace(LABEL_NOISE, " ").replace(WS, " ").trim()
}
