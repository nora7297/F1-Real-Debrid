package com.f1debrid.core.sync

import com.f1debrid.core.model.Quality
import com.f1debrid.core.rd.RdStatus
import java.util.Locale

object Naming {
    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "ts", "m2ts", "avi", "mov", "webm", "wmv", "mpg", "mpeg")
    private val SAMPLE = Regex("""(^|[\s._\-])sample([\s._\-]|$)""", RegexOption.IGNORE_CASE)

    fun isVideoFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
    fun isSample(path: String): Boolean = SAMPLE.containsMatchIn(path.substringAfterLast('/'))

    fun eventTitle(e: CatalogEntry): String {
        val parts = ArrayList<String>()
        e.year?.let { parts += it.toString() }
        when {
            e.gpName != null -> parts += "${e.gpName} Grand Prix"
            e.round != null -> parts += "Round ${e.round}"
        }
        if (parts.isEmpty()) return e.title
        val base = parts.joinToString(" ")
        return if (e.gpName != null && e.round != null) "$base (Round ${e.round})" else base
    }

    fun entryTitle(e: CatalogEntry): String = buildString {
        append(e.sessionLabel)
        if (e.quality != Quality.UNKNOWN) append(" · ").append(e.quality.label)
        e.broadcaster?.let { append(" · ").append(it) }
    }

    fun fileTitle(f: CatalogFile): String {
        if (f.sessions.isNotEmpty()) {
            return buildString {
                append(f.primarySession.let { if (f.sessions.size > 1) com.f1debrid.core.model.SessionType.labelOf(f.sessions) else it.label })
                if (f.quality != Quality.UNKNOWN) append(" · ").append(f.quality.label)
            }
        }
        return f.name.substringBeforeLast('.').replace('.', ' ').replace('_', ' ').trim().ifBlank { f.name }
    }

    fun statusText(e: CatalogEntry): String = when (e.rdStatus) {
        RdStatus.DOWNLOADED -> if (e.files.any { it.link != null }) "Ready to play" else "Ready (no links yet)"
        RdStatus.DOWNLOADING -> "Downloading on Real-Debrid ${e.progress.toInt()}%"
        RdStatus.QUEUED -> "Queued on Real-Debrid"
        RdStatus.MAGNET_CONVERSION -> "Processing magnet"
        RdStatus.WAITING_FILES_SELECTION -> "Selecting files"
        RdStatus.COMPRESSING, RdStatus.UPLOADING -> "Finishing on Real-Debrid"
        RdStatus.MISSING -> "Removed from Real-Debrid"
        RdStatus.MAGNET_ERROR -> "Bad magnet link"
        RdStatus.ERROR, RdStatus.VIRUS, RdStatus.DEAD -> "Failed (${e.rdStatus})"
        null -> e.rdError?.let { "Not added: $it" } ?: "Waiting to be added"
        else -> e.rdStatus.replace('_', ' ')
    }

    fun humanBytes(bytes: Long): String {
        if (bytes <= 0) return ""
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb)
        val mb = bytes / 1_048_576.0
        return String.format(Locale.US, "%.0f MB", mb)
    }

    fun relativeTime(then: Long, now: Long): String {
        if (then <= 0) return "never"
        val diff = (now - then).coerceAtLeast(0)
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }
}
