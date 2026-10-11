package com.f1debrid.core

import com.f1debrid.core.model.Quality
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.parse.F1TitleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class F1TitleParserTest {

    @Test
    fun skyUhdRace() {
        val p = F1TitleParser.parse("Formula 1 2026 Round 19 United States Grand Prix Race Sky Sports F1 UHD 2160p")
        assertTrue(p.isF1)
        assertEquals(Quality.UHD_2160, p.quality)
        assertEquals(listOf(SessionType.RACE), p.sessions)
        assertEquals(2026, p.year)
        assertEquals(19, p.round)
        assertEquals("United States", p.gpName)
        assertEquals("Sky Sports F1", p.broadcaster)
    }

    @Test
    fun bracketedF1tvQualifying() {
        val p = F1TitleParser.parse("[F1] 2026 R19 - United States GP - Qualifying - F1TV 1080p50")
        assertTrue(p.isF1)
        assertEquals(Quality.FHD_1080, p.quality)
        assertEquals(listOf(SessionType.QUALIFYING), p.sessions)
        assertEquals(19, p.round)
        assertEquals("United States", p.gpName)
        assertEquals("F1TV", p.broadcaster)
    }

    @Test
    fun torrentStyleName() {
        val p = F1TitleParser.parse("F1.2026.R19.United.States.Grand.Prix.Sprint.Qualifying.SkyF1UHD.2160p.mkv")
        assertTrue(p.isF1)
        assertEquals(listOf(SessionType.SPRINT_QUALIFYING), p.sessions)
        assertEquals(Quality.UHD_2160, p.quality)
        assertEquals(19, p.round)
        assertEquals("United States", p.gpName)
    }

    @Test
    fun sprintRaceIsNotTheRace() {
        val p = F1TitleParser.parse("F1 2026 Qatar GP Sprint Race Sky 1080p")
        assertEquals(listOf(SessionType.SPRINT), p.sessions)
        assertEquals("Qatar", p.gpName)
    }

    @Test
    fun preAndPostRaceShowsDoNotTurnIntoWeekend() {
        val p = F1TitleParser.parse("Formula 1 2026 Monaco Grand Prix Pre-Race Show + Race + Post-Race Sky 1080p")
        assertEquals(setOf(SessionType.PRE_RACE, SessionType.RACE, SessionType.POST_RACE), p.sessions.toSet())
        assertEquals(SessionType.RACE, p.primarySession)
        assertEquals("Pre-Race Show + Race + Post-Race Show", p.sessionLabel)
    }

    @Test
    fun threeCoreSessionsBecomeWeekend() {
        val p = F1TitleParser.parse("F1 2026 Round 03 Japanese GP FP3 + Qualifying + Race Sky 2160p")
        assertEquals(SessionType.WEEKEND, p.primarySession)
    }

    @Test
    fun weekendPack() {
        val p = F1TitleParser.parse("Formula 1 2026 R19 United States Grand Prix Weekend Sky Sports F1 UHD")
        assertEquals(listOf(SessionType.WEEKEND), p.sessions)
        assertEquals(Quality.UHD_2160, p.quality)
    }

    @Test
    fun excludesF1Academy() {
        assertFalse(F1TitleParser.parse("F1 Academy 2026 Round 5 Race 1 Sky 1080p").isF1)
    }

    @Test
    fun excludesFormula2EvenOnF1tv() {
        assertFalse(F1TitleParser.parse("Formula 2 2026 Austin Feature Race F1TV 1080p").isF1)
    }

    @Test
    fun excludesMotoGp() {
        assertFalse(F1TitleParser.parse("MotoGP 2026 Round 15 Race TNT Sports 1080p").isF1)
    }

    @Test
    fun flairDecidesWhenPresent() {
        assertTrue(F1TitleParser.parse("2026 Round 19 United States Grand Prix Race 1080p", flair = "Formula 1").isF1)
        assertFalse(F1TitleParser.parse("F1 2026 Round 19 Race 1080p", flair = "F1 Academy").isF1)
    }

    @Test
    fun grandPrixOfForm() {
        val p = F1TitleParser.parse("Formula 1 2026 Grand Prix of Monaco Race F1TV 1080p")
        assertEquals("Monaco", p.gpName)
        assertEquals(listOf(SessionType.RACE), p.sessions)
    }

    @Test
    fun multiWordNamesAndAbbreviations() {
        assertEquals("Emilia Romagna", F1TitleParser.parse("F1 2026 Emilia Romagna GP Race 1080p").gpName)
        assertEquals("Abu Dhabi", F1TitleParser.parse("Formula 1 2026 Round 24 Abu Dhabi Grand Prix Qualifying 2160p").gpName)
        assertEquals("USA", F1TitleParser.parse("F1 2026 USA GP Race 1080p").gpName)
    }

    @Test
    fun unknownQuality() {
        assertEquals(Quality.UNKNOWN, F1TitleParser.parse("F1 2026 Bahrain GP Race Sky").quality)
        assertEquals(Quality.HD_720, F1TitleParser.parse("F1 2026 Bahrain GP Race Sky 720p").quality)
    }

    @Test
    fun colonStyleRedditTitle() {
        val p = F1TitleParser.parse("2026 F1 R17: Singapore Grand Prix")
        assertTrue(p.isF1)
        assertEquals(2026, p.year)
        assertEquals(17, p.round)
        assertEquals("Singapore", p.gpName)
    }

    @Test
    fun eventKeyPrefersYearAndName() {
        val a = F1TitleParser.parse("Formula 1 2026 Round 19 United States Grand Prix Race Sky 2160p")
        val b = F1TitleParser.parse("[F1] 2026 United States GP Qualifying F1TV 1080p")
        assertEquals("2026-united-states", F1TitleParser.eventKey(a, "x"))
        assertEquals(F1TitleParser.eventKey(a, "x"), F1TitleParser.eventKey(b, "y"))
        val c = F1TitleParser.parse("F1 2026 Round 19 Race 1080p")
        assertEquals("2026-r19", F1TitleParser.eventKey(c, "z"))
    }
}
