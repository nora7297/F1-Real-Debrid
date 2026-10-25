package com.f1debrid.core.model

import kotlinx.serialization.Serializable

/** Video quality detected from a post title, magnet display name or file name. */
@Serializable
enum class Quality(val label: String, val rank: Int) {
    UHD_2160("4K", 3),
    FHD_1080("1080p", 2),
    HD_720("720p", 1),
    UNKNOWN("Unknown", 0);
}

/** Which part of a race weekend a release covers. */
@Serializable
enum class SessionType(val label: String, val order: Int) {
    FP1("Practice 1", 1),
    FP2("Practice 2", 2),
    FP3("Practice 3", 3),
    SPRINT_QUALIFYING("Sprint Qualifying", 4),
    SPRINT("Sprint", 5),
    QUALIFYING("Qualifying", 6),
    PRE_RACE("Pre-Race Show", 7),
    RACE("Race", 8),
    POST_RACE("Post-Race Show", 9),
    WEEKEND("Full Weekend", 10),
    OTHER("Other", 11);

    companion object {
        private val SHOWS = setOf(PRE_RACE, POST_RACE)

        /** The session used for grouping / filtering when a release covers several. */
        fun primaryOf(sessions: List<SessionType>): SessionType {
            if (sessions.isEmpty()) return OTHER
            if (WEEKEND in sessions) return WEEKEND
            val core = sessions.filter { it !in SHOWS }
            if (core.size >= 3) return WEEKEND
            return (core.ifEmpty { sessions }).minBy { it.order }
        }

        fun labelOf(sessions: List<SessionType>): String {
            if (sessions.isEmpty()) return OTHER.label
            if (WEEKEND in sessions) return WEEKEND.label
            val core = sessions.distinct().filter { it !in SHOWS }
            if (core.size >= 3) return WEEKEND.label
            // Build-up and post-session shows ride along with the main session; name them only on their own.
            val named = core.ifEmpty { sessions.distinct() }
            return named.sortedBy { it.order }.joinToString(" + ") { it.label }
        }
    }
}

/** How the sync decides which qualities to add to Real-Debrid. */
@Serializable
enum class QualityPolicy(val label: String) {
    PREFER_UHD("4K preferred, otherwise 1080p"),
    BOTH("Both 4K and 1080p"),
    UHD_ONLY("4K only"),
    FHD_ONLY("1080p only");

    fun allows(q: Quality): Boolean = when (this) {
        PREFER_UHD, BOTH -> q == Quality.UHD_2160 || q == Quality.FHD_1080
        UHD_ONLY -> q == Quality.UHD_2160
        FHD_ONLY -> q == Quality.FHD_1080
    }
}
