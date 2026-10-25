package com.f1debrid.core

import com.f1debrid.core.model.Quality
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.rd.RdTorrentFile
import com.f1debrid.core.rd.RdTorrentInfo
import com.f1debrid.core.sync.CatalogEntry
import com.f1debrid.core.sync.Naming
import com.f1debrid.core.sync.withInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class LabelTest {
    private fun entry(title: String) = CatalogEntry(
        hash = "a".repeat(40), magnet = "magnet:?xt=urn:btih:" + "a".repeat(40), title = title,
        postId = "p", postUrl = "u", eventKey = "2026-united-states", quality = Quality.UHD_2160,
    )

    private fun info(name: String, vararg files: String) = RdTorrentInfo(
        id = "rd1", filename = name, status = "downloaded", progress = 100.0,
        files = files.mapIndexed { i, f -> RdTorrentFile(i + 1, "/$name/$f", 1_000_000_000L, 1) },
        links = files.indices.map { "https://real-debrid.com/d/L$it" },
    )

    @Test
    fun sessionComesFromTheFilesWhenThePostAndTorrentNameHaveNone() {
        val e = entry("Formula 1 2026 R18 United States Sky Sports F1 UHD").withInfo(
            info(
                "Formula1.2026.Round18.UnitedStates.SkyF1UHD.2160P",
                "01.Qualifying.Build-Up.mkv", "02.Qualifying.mkv", "03.Post-Qualifying.Analysis.mkv",
            ),
            now = 1L,
        )
        assertEquals(SessionType.QUALIFYING, e.primarySession)
        assertEquals("Qualifying · 3 files · 4K · Sky Sports F1", Naming.entryTitle(e))
    }

    @Test
    fun torrentNameBeatsTheFiles() {
        val e = entry("Formula 1 2026 R18 United States").withInfo(
            info("Formula1.2026.Round18.UnitedStates.Race.SkyF1UHD.2160P", "01.mkv", "02.mkv"),
            now = 1L,
        )
        assertEquals(listOf(SessionType.RACE), e.sessions)
        assertEquals("Race · 2 files · 4K · Sky Sports F1", Naming.entryTitle(e))
    }

    @Test
    fun practiceFilesTogetherMakeAWeekend() {
        val e = entry("Formula 1 2026 R18 United States").withInfo(
            info("Formula1.2026.Round18.UnitedStates.SkyF1UHD", "FP1.mkv", "FP2.mkv", "FP3.mkv"),
            now = 1L,
        )
        assertEquals(SessionType.WEEKEND, e.primarySession)
        assertEquals("Full Weekend · 3 files · 4K · Sky Sports F1", Naming.entryTitle(e))
    }

    @Test
    fun nothingRecognisableStaysGeneric() {
        val single = entry("Formula 1 2026 R18 United States").withInfo(info("F1.2026.R18.SkyF1UHD", "a.mkv"), now = 1L)
        assertEquals("Other · 4K · Sky Sports F1", Naming.entryTitle(single))
        val pack = entry("Formula 1 2026 R18 United States").withInfo(info("F1.2026.R18.SkyF1UHD", "a.mkv", "b.mkv"), now = 1L)
        assertEquals("Weekend pack · 2 files · 4K · Sky Sports F1", Naming.entryTitle(pack))
    }
}
