package com.f1debrid.core

import com.f1debrid.core.model.Quality
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.parse.GrandPrix
import com.f1debrid.core.sync.CatalogEntry
import com.f1debrid.core.sync.CatalogGrouping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GrandPrixTest {
    @Test
    fun namesForTheSameRaceAgree() {
        for (n in listOf("Mexico City", "Mexican", "Mexican States", "United Mexican States", "Mexico")) {
            assertEquals("mexico", GrandPrix.find(n)?.key, n)
        }
        for (n in listOf("United States", "USA", "US", "American", "Austin")) {
            assertEquals("united-states", GrandPrix.find(n)?.key, n)
        }
        for (n in listOf("São Paulo", "Sao Paulo", "Brazilian", "Brazil")) {
            assertEquals("brazil", GrandPrix.find(n)?.key, n)
        }
        assertEquals("las-vegas", GrandPrix.find("Las Vegas")?.key)
        assertEquals("abu-dhabi", GrandPrix.find("Abu Dhabi")?.key)
        assertEquals("britain", GrandPrix.find("Great Britain")?.key)
        assertEquals("saudi-arabia", GrandPrix.find("Saudi Arabian")?.key)
        assertEquals("spain", GrandPrix.find("Spanish")?.key)
        assertEquals("barcelona", GrandPrix.find("Barcelona-Catalunya")?.key)
        assertEquals("Mexico City", GrandPrix.find("Mexican")?.display)
    }

    @Test
    fun unknownNamesStayUnknown() {
        assertNull(GrandPrix.find("Narnia"))
        assertNull(GrandPrix.find(""))
        assertNull(GrandPrix.find(null))
    }

    private fun entry(hash: Char, gp: String, round: Int?, key: String, quality: Quality = Quality.UHD_2160) = CatalogEntry(
        hash = hash.toString().repeat(40), magnet = "m", title = "t", postId = "p", postUrl = "u",
        year = 2026, gpName = gp, round = round, eventKey = key, quality = quality, sessions = listOf(SessionType.QUALIFYING),
    )

    @Test
    fun differentlyNamedPostsOfOneRaceShareAGroup() {
        val groups = CatalogGrouping.group(
            listOf(
                entry('a', "Mexico City", null, "2026-mexico-city"),
                entry('b', "Mexican", 19, "2026-mexican"),
                entry('c', "Mexican States", 19, "2026-mexican-states", Quality.FHD_1080),
                entry('d', "United States", 18, "2026-united-states"),
            ),
        )
        assertEquals(2, groups.size)
        val mexico = groups.single { it.eventKey == "2026-mexico" }
        assertEquals(3, mexico.entries.size)
        assertEquals("2026 Mexico City Grand Prix (Round 19)", mexico.title)
    }
}
