package com.f1debrid.core.parse

import com.f1debrid.core.model.Quality
import com.f1debrid.core.model.SessionType

data class ParsedTitle(
    val isF1: Boolean,
    val quality: Quality,
    val sessions: List<SessionType>,
    val year: Int?,
    val round: Int?,
    val gpName: String?,
    val broadcaster: String?,
) {
    val primarySession: SessionType get() = SessionType.primaryOf(sessions)
    val sessionLabel: String get() = SessionType.labelOf(sessions)
}

/**
 * Understands the (fairly free-form) titles used on r/MotorsportsReplays and in torrent names, e.g.
 * "Formula 1 2026 Round 19 United States Grand Prix Race Sky Sports F1 UHD 2160p" or
 * "F1.2026.R19.United.States.GP.Qualifying.F1TV.1080p50".
 */
object F1TitleParser {
    private val I = RegexOption.IGNORE_CASE

    private val ACADEMY = Regex("""F1\s*Academy""", I)
    private val F1 = Regex("""\bF1\b|\bFormula\s*(?:1|One)\b|\bFormula1\b""", I)
    private val F1_FLAIR = Regex("""^\s*(?:F1|Formula\s*(?:1|One))\s*$""", I)
    private val OTHER_SERIES_FLAIR = Regex(
        """Academy|Formula\s*[234E]\b|\bF[234]\b|\bFE\b|Indy|MotoGP|Moto[23]|NASCAR|\bWEC\b|\bIMSA\b|\bDTM\b|\bWRC\b|\bBTCC\b|Super\s*Formula|W\s*Series|Supercup|\bGT\b|Supercars|Rally""",
        I,
    )

    private val UHD = Regex("""2160p?|\b4K\b|UHD""", I)
    private val FHD = Regex("""1080[pi]?\d{0,2}\b|\bFHD\b|Full\s*HD""", I)
    private val HD = Regex("""720p""", I)

    private val YEAR = Regex("""\b(20[0-9]{2})\b""")
    private val ROUND = Regex("""\b(?:Round|Rnd|Rd|R)\s*\.?\s*0?(\d{1,2})\b""", I)
    private val GP = Regex("""\b(?:Grand\s*Prix|GP)\b""", I)
    private val GP_OF = Regex("""^\s*(?:of|de|du|di)\s+([\p{L}][\p{L}'.\-]*(?:\s+[\p{L}][\p{L}'.\-]*){0,3})""", I)
    private val DOT_SEPARATED = Regex("""(?<=\w)[._](?=\w)""")
    private val NAME_SPLIT = Regex("""[\s\-–—|:,/\[\]()]+""")

    private val SESSION_PATTERNS: List<Pair<SessionType, Regex>> = listOf(
        SessionType.SPRINT_QUALIFYING to Regex("""\bSprint\s*(?:Qualifying|Quali|Qualy|Shootout|Shoot-?Out)\b|\bSQ\b""", I),
        SessionType.FP1 to Regex("""\bFP\s*1\b|\b(?:Free\s*)?Practice\s*(?:1|One)\b""", I),
        SessionType.FP2 to Regex("""\bFP\s*2\b|\b(?:Free\s*)?Practice\s*(?:2|Two)\b""", I),
        SessionType.FP3 to Regex("""\bFP\s*3\b|\b(?:Free\s*)?Practice\s*(?:3|Three)\b""", I),
        SessionType.PRE_RACE to Regex("""\bPre[\s\-]?(?:Race|Show|Qualifying|Quali)\b|\bBuild[\s\-]?Up\b|\bGrid\s*Walk\b""", I),
        SessionType.POST_RACE to Regex("""\bPost[\s\-]?(?:Race|Show|Qualifying|Quali)\b|\bNotebook\b|\bChequered\s*Flag\b|\bCheckered\s*Flag\b|\bDebrief\b""", I),
        SessionType.SPRINT to Regex("""\bSprint(?:\s*Race)?\b""", I),
        SessionType.QUALIFYING to Regex("""\bQualifying\b|\bQuali\b|\bQualy\b|\bQualification\b""", I),
        SessionType.WEEKEND to Regex("""\bWeekend\b|\bAll\s*Sessions\b|\bComplete\s*Pack\b|\bFull\s*Pack\b|\bWhole\s*Event\b""", I),
        SessionType.RACE to Regex("""\bRace\b""", I),
    )

    private val BROADCASTERS: List<Pair<String, Regex>> = listOf(
        "Sky Sports F1" to Regex("""\bSky\b|SkyF1|Sky\s*Sports""", I),
        "F1TV" to Regex("""\bF1\s*TV\b|\bF1TV\b""", I),
        "Channel 4" to Regex("""\bChannel\s*4\b|\bC4\b""", I),
        "ServusTV" to Regex("""Servus\s*TV""", I),
        "RTL" to Regex("""\bRTL\b""", I),
        "ORF" to Regex("""\bORF\b""", I),
        "Viaplay" to Regex("""Viaplay""", I),
        "ESPN" to Regex("""\bESPN\b""", I),
        "Canal+" to Regex("""Canal\s*\+""", I),
        "DAZN" to Regex("""\bDAZN\b""", I),
        "Ziggo" to Regex("""Ziggo""", I),
        "beIN" to Regex("""\bbeIN\b""", I),
    )

    private val NAME_STOP = setOf(
        "f1", "formula", "one", "round", "rnd", "rd", "race", "the", "sky", "sports", "f1tv", "weekend", "season",
        "hd", "uhd", "fhd", "and", "of", "full", "pack", "replay", "replays", "sprint", "qualifying", "quali", "fp1",
        "fp2", "fp3", "practice", "pre", "post", "show", "tv", "web", "webrip", "hdtv", "x264", "x265", "hevc",
        "complete", "entire", "whole", "event", "new", "updated", "fixed",
    )

    fun parse(rawTitle: String, flair: String? = null): ParsedTitle {
        val title = normalize(rawTitle)
        val isF1 = when {
            flair != null && F1_FLAIR.containsMatchIn(flair) -> true
            flair != null && OTHER_SERIES_FLAIR.containsMatchIn(flair) -> false
            else -> F1.containsMatchIn(ACADEMY.replace(title, " "))
        }
        val quality = when {
            UHD.containsMatchIn(title) -> Quality.UHD_2160
            FHD.containsMatchIn(title) -> Quality.FHD_1080
            HD.containsMatchIn(title) -> Quality.HD_720
            else -> Quality.UNKNOWN
        }
        var work = title
        val sessions = ArrayList<SessionType>()
        for ((type, re) in SESSION_PATTERNS) {
            if (re.containsMatchIn(work)) {
                sessions += type
                work = re.replace(work, " ")
            }
        }
        val year = YEAR.find(title)?.groupValues?.get(1)?.toIntOrNull()
        val round = ROUND.find(title)?.groupValues?.get(1)?.toIntOrNull()
        val broadcaster = BROADCASTERS.firstOrNull { it.second.containsMatchIn(title) }?.first
        return ParsedTitle(isF1, quality, sessions, year, round, gpName(title), broadcaster)
    }

    /** Turns torrent-style names ("F1.2026.R19.United.States") into something word-based. */
    fun normalize(s: String): String = DOT_SEPARATED.replace(s, " ").trim()

    private fun gpName(title: String): String? {
        val m = GP.find(title) ?: return null
        val before = title.substring(0, m.range.first)
        val tokens = before.split(NAME_SPLIT).filter { it.isNotBlank() }
        val picked = ArrayList<String>()
        for (tok in tokens.asReversed()) {
            val t = tok.trim('.', '\'', '"', '*')
            if (t.isEmpty()) break
            if (!t.all { it.isLetter() || it == '\'' || it == '.' || it == '-' }) break
            if (t.lowercase() in NAME_STOP) break
            picked.add(0, t)
            if (picked.size == 4) break
        }
        if (picked.isEmpty()) {
            val after = title.substring(m.range.last + 1)
            val of = GP_OF.find(after) ?: return null
            for (tok in of.groupValues[1].split(NAME_SPLIT)) {
                val t = tok.trim('.', '\'', '"', '*')
                if (t.isEmpty() || t.lowercase() in NAME_STOP || !t.all { it.isLetter() || it == '\'' || it == '-' }) break
                picked += t
                if (picked.size == 4) break
            }
            if (picked.isEmpty()) return null
        }
        return picked.joinToString(" ") { w ->
            if (w.length <= 3 && w.all { it.isUpperCase() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    /** Stable key used to group releases that belong to the same race weekend. */
    fun eventKey(p: ParsedTitle, fallback: String): String = when {
        p.year != null && p.gpName != null -> "${p.year}-${slug(p.gpName)}"
        p.year != null && p.round != null -> "${p.year}-r${p.round.toString().padStart(2, '0')}"
        p.gpName != null -> "unknown-${slug(p.gpName)}"
        p.round != null -> "unknown-r${p.round.toString().padStart(2, '0')}"
        else -> "misc-" + slug(fallback).take(40)
    }

    fun slug(s: String): String = s.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
}
