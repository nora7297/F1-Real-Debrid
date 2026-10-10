package com.f1debrid.core

import com.f1debrid.core.magnet.Magnets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MagnetsTest {
    private val hex = "0123456789abcdef0123456789abcdef01234567"
    private val hex2 = "89abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun extractsPlainMagnetWithLabel() {
        val text = "Some intro\n\n**Race**\n\nmagnet:?xt=urn:btih:$hex&dn=F1.2026.R19.Race.2160p&tr=udp%3A%2F%2Ftracker.example%3A80\n\nEnjoy"
        val links = Magnets.extract(text)
        assertEquals(1, links.size)
        assertEquals(hex, links[0].infoHash)
        assertEquals("F1.2026.R19.Race.2160p", links[0].displayName)
        assertEquals("Race", links[0].contextLabel)
        assertTrue(links[0].raw.startsWith("magnet:?xt=urn:btih:$hex&dn="))
    }

    @Test
    fun unescapesRedditMarkdown() {
        val text = "magnet:?xt=urn:btih:$hex&amp;dn=F1\\_2026\\_Race&amp;tr=udp%3A%2F%2Ft.example"
        val links = Magnets.extract(text)
        assertEquals(1, links.size)
        assertEquals("magnet:?xt=urn:btih:$hex&dn=F1_2026_Race&tr=udp%3A%2F%2Ft.example", links[0].raw)
        assertEquals("F1_2026_Race", links[0].displayName)
    }

    @Test
    fun markdownLinkLabel() {
        val text = "Links:\n\n* [Qualifying 1080p](magnet:?xt=urn:btih:$hex)\n* [Race 1080p](magnet:?xt=urn:btih:$hex2)"
        val links = Magnets.extract(text)
        assertEquals(2, links.size)
        assertEquals("Qualifying 1080p", links[0].contextLabel)
        assertEquals("Race 1080p", links[1].contextLabel)
        assertEquals(hex2, links[1].infoHash)
    }

    @Test
    fun base32HashIsNormalisedToHex() {
        val link = Magnets.parse("magnet:?xt=urn:btih:AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH&dn=x")
        assertEquals(hex, link?.infoHash)
        assertEquals("00000000000000000000000000000000000000ff", Magnets.base32ToHex("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAH7"))
        assertNull(Magnets.base32ToHex("not-base32-at-all-really-not-at!"))
    }

    @Test
    fun upperCaseHexIsLowered() {
        assertEquals(hex, Magnets.parse("magnet:?xt=urn:btih:${hex.uppercase()}")?.infoHash)
    }

    @Test
    fun duplicatesCollapse() {
        val text = "magnet:?xt=urn:btih:$hex&dn=a\n\nmagnet:?xt=urn:btih:${hex.uppercase()}&dn=b"
        assertEquals(1, Magnets.extract(text).size)
    }

    @Test
    fun ignoresGarbage() {
        assertTrue(Magnets.extract("no links here, magnet:?xt=urn:btih:tooshort").isEmpty())
        assertTrue(Magnets.extract(null).isEmpty())
        assertFalse(Magnets.extract("magnet:?xt=urn:btih:$hex").isEmpty())
    }
}
