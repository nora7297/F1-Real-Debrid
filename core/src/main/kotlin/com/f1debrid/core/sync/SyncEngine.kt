package com.f1debrid.core.sync

import com.f1debrid.core.magnet.MagnetLink
import com.f1debrid.core.magnet.Magnets
import com.f1debrid.core.model.Quality
import com.f1debrid.core.model.QualityPolicy
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.parse.F1TitleParser
import com.f1debrid.core.parse.ParsedTitle
import com.f1debrid.core.rd.RdApiException
import com.f1debrid.core.rd.RdStatus
import com.f1debrid.core.rd.RdTorrentFile
import com.f1debrid.core.rd.RealDebridClient
import com.f1debrid.core.reddit.RateLimitedException
import com.f1debrid.core.reddit.RedditPost
import com.f1debrid.core.reddit.RedditSource
import kotlinx.coroutines.delay
import java.io.IOException

data class SyncSettings(
    val subreddit: String = "MotorsportsReplays",
    val lookbackDays: Int = 21,
    val maxPages: Int = 3,
    val pageSize: Int = 100,
    val qualityPolicy: QualityPolicy = QualityPolicy.PREFER_UHD,
    val enabledSessions: Set<SessionType> = DEFAULT_SESSIONS,
    val maxNewPerSync: Int = 20,
    val fetchComments: Boolean = true,
    val maxCommentFetches: Int = 5,
    val includeUnknownQuality: Boolean = false,
) {
    companion object {
        val DEFAULT_SESSIONS: Set<SessionType> = setOf(
            SessionType.RACE, SessionType.QUALIFYING, SessionType.SPRINT, SessionType.SPRINT_QUALIFYING,
            SessionType.WEEKEND, SessionType.OTHER,
        )
    }
}

data class SyncReport(
    val scannedPosts: Int,
    val f1Posts: Int,
    val candidates: Int,
    val added: Int,
    val updated: Int,
    val errors: List<String>,
    val durationMs: Long,
) {
    val summary: String
        get() = buildString {
            append("Scanned ").append(scannedPosts).append(" posts (").append(f1Posts).append(" F1)")
            append(", added ").append(added)
            if (updated > 0) append(", updated ").append(updated)
            if (errors.isNotEmpty()) append(", ").append(errors.size).append(if (errors.size == 1) " error" else " errors")
        }
}

/**
 * One full pass: read the subreddit, pick F1 releases in the wanted quality, add anything new to
 * Real-Debrid, select the video files, and refresh the status of releases still in progress.
 */
class SyncEngine(
    private val reddit: RedditSource,
    private val rd: RealDebridClient,
    private val store: CatalogStore,
    private val settings: () -> SyncSettings,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private class Candidate(
        val post: RedditPost,
        val magnet: MagnetLink,
        val quality: Quality,
        val sessions: List<SessionType>,
        val year: Int?,
        val round: Int?,
        val gpName: String?,
        val broadcaster: String?,
        val eventKey: String,
    ) {
        val hash: String get() = magnet.infoHash
        val primarySession: SessionType get() = SessionType.primaryOf(sessions)
        val groupKey: String get() = "$eventKey|$primarySession"
        val displayTitle: String
            get() = listOfNotNull(gpName?.let { "$it GP" }, SessionType.labelOf(sessions), quality.label).joinToString(" · ")
    }

    /**
     * @param quick read only the newest page of posts and do fewer comment look-ups; used for the
     *   frequent refreshes while the app is open, to stay inside Reddit's anonymous rate limit.
     */
    suspend fun sync(quick: Boolean = false, onProgress: suspend (String) -> Unit = {}): SyncReport {
        val s = settings().let { if (quick) it.copy(maxPages = 1, maxCommentFetches = minOf(it.maxCommentFetches, 2)) else it }
        val startedAt = clock()
        val errors = ArrayList<String>()
        var catalog = store.load()
        // Releases whose add failed in an earlier run; retried below (never the ones added in this run).
        val retryHashes = catalog.entries.filter { it.rdTorrentId == null && it.addAttempts in 1..4 }.map { it.hash }

        onProgress("Checking r/${s.subreddit}…")
        val posts = fetchPosts(s, startedAt, errors)
        log("Fetched ${posts.size} posts")

        val commentsChecked = catalog.commentsChecked.toMutableMap()
        var commentFetches = 0
        var commentFailures = 0
        var lastCommentError: String? = null
        val candidates = ArrayList<Candidate>()
        var f1Posts = 0
        for (post in posts) {
            val parsed = F1TitleParser.parse(post.title, post.linkFlairText)
            if (!parsed.isF1) continue
            f1Posts++
            var magnets = Magnets.extract(post.selftext)
            if (magnets.isEmpty()) magnets = Magnets.extract(post.url?.takeIf { it.startsWith("magnet:", ignoreCase = true) })
            if (magnets.isEmpty() && s.fetchComments && commentFetches < s.maxCommentFetches &&
                shouldCheckComments(post, commentsChecked, startedAt)
            ) {
                commentFetches++
                try {
                    onProgress("Reading comments: ${post.title.take(50)}")
                    magnets = reddit.fetchCommentBodies(post.permalink).flatMap { Magnets.extract(it) }.distinctBy { it.infoHash }
                    commentsChecked[post.id] = startedAt
                } catch (e: Exception) {
                    commentFailures++
                    lastCommentError = e.message
                    // Reddit is usually rate-limiting at this point; stop spending requests on comments this run.
                    commentFetches = s.maxCommentFetches
                }
            }
            val useContext = magnets.size > 1
            for (m in magnets) candidates += candidate(post, parsed, m, useContext)
        }
        if (commentFailures > 0) {
            val hint = if (lastCommentError?.contains("rate limit", ignoreCase = true) == true) {
                " Tip: a Reddit app client ID (Settings) removes this limit."
            } else ""
            errors += "Could not read the comments of $commentFailures post(s), will retry next sync: ${lastCommentError?.take(200)}$hint"
        }
        log("${candidates.size} magnet candidates from $f1Posts F1 posts")

        val known = catalog.entries.map { it.hash }.toHashSet()
        val fresh = candidates
            .sortedByDescending { it.post.createdMillis }
            .distinctBy { it.hash }
            .filter { it.hash !in known && it.hash !in catalog.ignoredHashes }
            .filter { s.qualityPolicy.allows(it.quality) || (s.includeUnknownQuality && it.quality == Quality.UNKNOWN) }
            .filter { it.primarySession in s.enabledSessions }
        val toAdd = applyPolicy(fresh, catalog.entries, s.qualityPolicy).take(s.maxNewPerSync)

        var added = 0
        if (toAdd.isNotEmpty()) {
            val existing = try {
                existingRdTorrents()
            } catch (e: Exception) {
                errors += "Could not list Real-Debrid torrents: ${e.message}"
                emptyMap()
            }
            for (c in toAdd) {
                onProgress("Adding ${c.displayTitle}")
                val entry = addToRealDebrid(newEntry(c, startedAt), existing[c.hash], errors)
                catalog = catalog.upsert(entry)
                store.save(catalog)
                added++
            }
        }

        for (hash in retryHashes) {
            val e = catalog.find(hash) ?: continue
            if (e.rdTorrentId != null) continue
            onProgress("Retrying ${Naming.entryTitle(e)}")
            catalog = catalog.upsert(addToRealDebrid(e, null, errors))
            store.save(catalog)
        }

        var updated = 0
        val pending = catalog.entries.filter { it.rdTorrentId != null && RdStatus.isPending(it.rdStatus) }.take(30)
        if (pending.isNotEmpty()) onProgress("Refreshing ${pending.size} in-progress release(s)")
        for (e in pending) {
            val id = e.rdTorrentId ?: continue
            try {
                catalog = catalog.upsert(e.withInfo(rd.torrentInfo(id), clock()))
                updated++
            } catch (ex: RdApiException) {
                if (ex.httpCode == 404) {
                    catalog = catalog.upsert(e.copy(rdStatus = RdStatus.MISSING, rdError = "Removed from Real-Debrid", updatedAt = clock()))
                } else {
                    errors += "Status of ${Naming.entryTitle(e)}: ${ex.message}"
                }
            } catch (ex: Exception) {
                errors += "Status of ${Naming.entryTitle(e)}: ${ex.message}"
            }
        }

        val report = SyncReport(posts.size, f1Posts, candidates.size, added, updated, errors, clock() - startedAt)
        val keepAfter = startedAt - 30L * DAY_MS
        catalog = catalog.copy(
            entries = catalog.entries.map { it.withDerivedLabels() },
            lastSyncAt = startedAt,
            lastSyncSummary = report.summary,
            lastSyncErrors = errors.toList(),
            commentsChecked = commentsChecked.filterValues { it > keepAfter },
        )
        store.save(catalog)
        log(report.summary)
        return report
    }

    /** Re-check a single release against Real-Debrid (used by the details screen). */
    suspend fun refreshEntry(hash: String): CatalogEntry? {
        var catalog = store.load()
        val entry = catalog.find(hash) ?: return null
        val id = entry.rdTorrentId
        val updated = if (id == null) {
            addToRealDebrid(entry, null, ArrayList())
        } else {
            try {
                entry.withInfo(rd.torrentInfo(id), clock())
            } catch (ex: RdApiException) {
                if (ex.httpCode == 404) entry.copy(rdStatus = RdStatus.MISSING, rdError = "Removed from Real-Debrid", updatedAt = clock())
                else entry.copy(rdError = ex.message, updatedAt = clock())
            }
        }
        catalog = catalog.upsert(updated)
        store.save(catalog)
        return updated
    }

    /** Add the release to Real-Debrid again (after it was deleted there, or after a failure). */
    suspend fun readd(hash: String): CatalogEntry? {
        var catalog = store.load()
        val entry = catalog.find(hash) ?: return null
        val reset = entry.copy(rdTorrentId = null, rdStatus = null, rdError = null, files = emptyList(), progress = 0.0)
        val updated = addToRealDebrid(reset, null, ArrayList())
        catalog = catalog.upsert(updated)
        store.save(catalog)
        return updated
    }

    private suspend fun fetchPosts(s: SyncSettings, now: Long, errors: MutableList<String>): List<RedditPost> {
        val cutoff = now - s.lookbackDays.toLong() * DAY_MS
        val out = ArrayList<RedditPost>()
        var after: String? = null
        for (page in 0 until s.maxPages) {
            val result = try {
                reddit.fetchNew(s.subreddit, s.pageSize, after)
            } catch (e: Exception) {
                if (page == 0) {
                    val hint = if (e is RateLimitedException) " Tip: a Reddit app client ID (Settings) removes this limit." else ""
                    errors += "Reddit: ${e.message}$hint"
                }
                else log("Stopped paging after ${out.size} posts: ${e.message}")
                break
            }
            val recent = result.posts.filter { it.createdMillis >= cutoff }
            out += recent
            if (result.after == null || result.posts.isEmpty() || recent.size < result.posts.size) break
            after = result.after
        }
        return out
    }

    private fun shouldCheckComments(post: RedditPost, checked: Map<String, Long>, now: Long): Boolean {
        if (post.numComments == 0) return false
        val last = checked[post.id] ?: return true
        val age = now - post.createdMillis
        return age < 3 * DAY_MS && now - last > 12 * HOUR_MS
    }

    private fun candidate(post: RedditPost, title: ParsedTitle, magnet: MagnetLink, useContext: Boolean): Candidate {
        val dn = magnet.displayName?.let { F1TitleParser.parse(it) }
        val ctx = if (useContext) magnet.contextLabel?.let { F1TitleParser.parse(it) } else null
        val quality = listOfNotNull(dn?.quality, ctx?.quality, title.quality).firstOrNull { it != Quality.UNKNOWN } ?: Quality.UNKNOWN
        val sessions = listOfNotNull(dn?.sessions, ctx?.sessions, title.sessions).firstOrNull { it.isNotEmpty() } ?: emptyList()
        val year = title.year ?: dn?.year
        val round = title.round ?: dn?.round
        val gp = title.gpName ?: dn?.gpName
        val broadcaster = dn?.broadcaster ?: ctx?.broadcaster ?: title.broadcaster
        val merged = ParsedTitle(true, quality, sessions, year, round, gp, broadcaster)
        return Candidate(post, magnet, quality, sessions, year, round, gp, broadcaster, F1TitleParser.eventKey(merged, post.title))
    }

    private fun applyPolicy(fresh: List<Candidate>, existing: List<CatalogEntry>, policy: QualityPolicy): List<Candidate> {
        if (policy != QualityPolicy.PREFER_UHD) return fresh
        val uhdGroups = HashSet<String>()
        fresh.filter { it.quality == Quality.UHD_2160 }.mapTo(uhdGroups) { it.groupKey }
        existing.filter { it.quality == Quality.UHD_2160 && !it.isFailed }.mapTo(uhdGroups) { it.groupKey }
        return fresh.filter { it.quality == Quality.UHD_2160 || it.groupKey !in uhdGroups }
    }

    private fun newEntry(c: Candidate, now: Long) = CatalogEntry(
        hash = c.hash,
        magnet = c.magnet.raw,
        title = c.post.title,
        postId = c.post.id,
        postUrl = c.post.fullUrl,
        author = c.post.author,
        postedAt = c.post.createdMillis,
        quality = c.quality,
        sessions = c.sessions,
        year = c.year,
        round = c.round,
        gpName = c.gpName,
        broadcaster = c.broadcaster,
        eventKey = c.eventKey,
        addedAt = now,
        updatedAt = now,
    )

    private suspend fun existingRdTorrents(): Map<String, String> {
        val map = HashMap<String, String>()
        var page = 1
        while (page <= 5) {
            val list = rd.torrents(page, 100)
            for (t in list) if (t.hash.isNotBlank()) map.putIfAbsent(t.hash.lowercase(), t.id)
            if (list.size < 100) break
            page++
        }
        return map
    }

    private suspend fun addToRealDebrid(entry: CatalogEntry, existingId: String?, errors: MutableList<String>): CatalogEntry {
        return try {
            val id = existingId ?: rd.addMagnet(entry.magnet).id
            log("Real-Debrid id $id for ${entry.hash}" + if (existingId != null) " (already present)" else "")
            var info = rd.torrentInfo(id)
            var tries = 0
            while (info.status == RdStatus.MAGNET_CONVERSION && tries < 8) {
                sleep(1500)
                info = rd.torrentInfo(id)
                tries++
            }
            if (info.status == RdStatus.WAITING_FILES_SELECTION) {
                rd.selectFiles(id, pickVideoFileIds(info.files))
                tries = 0
                do {
                    sleep(1500)
                    info = rd.torrentInfo(id)
                    tries++
                } while (tries < 6 && (info.status == RdStatus.WAITING_FILES_SELECTION || info.status == RdStatus.MAGNET_CONVERSION || info.status == RdStatus.QUEUED))
            }
            entry.withInfo(info, clock()).copy(addAttempts = entry.addAttempts + 1)
        } catch (e: RdApiException) {
            errors += "Add ${Naming.entryTitle(entry)}: ${e.message}"
            entry.copy(rdError = e.message, addAttempts = entry.addAttempts + 1, updatedAt = clock())
        } catch (e: IOException) {
            errors += "Add ${Naming.entryTitle(entry)}: ${e.message}"
            entry.copy(rdError = e.message ?: "network error", addAttempts = entry.addAttempts + 1, updatedAt = clock())
        }
    }

    private fun pickVideoFileIds(files: List<RdTorrentFile>): List<Int>? {
        val videos = files.filter { Naming.isVideoFile(it.path) && !Naming.isSample(it.path) }.map { it.id }
        return videos.ifEmpty { null }
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
