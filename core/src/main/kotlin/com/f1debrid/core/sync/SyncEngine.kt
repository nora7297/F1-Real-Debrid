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
    /** 1 page = the newest 100 posts, about a week of the subreddit; more pages need a Reddit client ID. */
    val maxPages: Int = 1,
    val pageSize: Int = 100,
    val qualityPolicy: QualityPolicy = QualityPolicy.PREFER_UHD,
    val enabledSessions: Set<SessionType> = DEFAULT_SESSIONS,
    val maxNewPerSync: Int = 20,
    val fetchComments: Boolean = true,
    /** Comment threads read per sync; a weekend has about six session threads. */
    val maxCommentFetches: Int = 8,
    /** How long one sync may wait in total for Reddit's rate limit while reading comments. */
    val maxRateLimitWaitMs: Long = 150_000,
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
    /** One line per F1 release (or F1 post without a link) seen, newest first, saying what happened to it. */
    val findings: List<String> = emptyList(),
    /** Comment threads still to read because Reddit asked us to slow down; a follow-up sync reads them. */
    val deferredComments: Int = 0,
) {
    val summary: String
        get() = buildString {
            append("Scanned ").append(scannedPosts).append(" posts (").append(f1Posts).append(" F1)")
            append(", added ").append(added)
            if (updated > 0) append(", updated ").append(updated)
            if (deferredComments > 0) append(", ").append(deferredComments).append(" thread(s) to read on the next pass")
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
        val author: String?,
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
        val label: String
            get() = listOfNotNull(SessionType.labelOf(sessions), quality.label.takeIf { quality != Quality.UNKNOWN }, broadcaster)
                .joinToString(" · ")
    }

    suspend fun sync(onProgress: suspend (String) -> Unit = {}): SyncReport {
        val s = settings()
        val startedAt = clock()
        val errors = ArrayList<String>()
        var catalog = store.load()
        // Releases whose add failed in an earlier run; retried below (never the ones added in this run).
        val retryHashes = catalog.entries.filter { it.rdTorrentId == null && it.addAttempts in 1..4 }.map { it.hash }

        onProgress("Checking r/${s.subreddit}…")
        val posts = fetchPosts(s, startedAt, errors, onProgress)
        log("Fetched ${posts.size} posts")

        val commentsChecked = catalog.commentsChecked.toMutableMap()
        val scans = posts.mapNotNull { post ->
            val parsed = F1TitleParser.parse(post.title, post.linkFlairText)
            if (!parsed.isF1) return@mapNotNull null
            var body = Magnets.extract(post.selftext)
            if (body.isEmpty()) body = Magnets.extract(post.url?.takeIf { it.startsWith("magnet:", ignoreCase = true) })
            PostScan(post, parsed, body)
        }
        val f1Posts = scans.size

        // Comments: session threads (no link in the post) first, newest first, then recent posts that
        // already have links, since later sessions are often added as comments.
        val jobs = if (!s.fetchComments) emptyList() else
            scans.filter { it.bodyMagnets.isEmpty() && shouldCheckComments(it.post, commentsChecked, startedAt) }
                .sortedByDescending { it.post.createdMillis } +
                scans.filter { it.bodyMagnets.isNotEmpty() && shouldRecheckComments(it.post, commentsChecked, startedAt) }
                    .sortedByDescending { it.post.createdMillis }
        jobs.forEach { it.comments = CommentState.WAITING }
        var commentFailures = 0
        var lastCommentError: String? = null
        var waitedMs = 0L
        var reads = 0
        jobLoop@ for (job in jobs) {
            if (reads >= s.maxCommentFetches) break
            reads++
            while (true) {
                try {
                    onProgress("Reading comments: ${job.post.title.take(50)}")
                    for (c in reddit.fetchComments(job.post.permalink)) {
                        for (m in Magnets.extract(c.body)) job.commentMagnets += m to c.author
                    }
                    commentsChecked[job.post.id] = startedAt
                    job.comments = CommentState.READ
                    break
                } catch (e: RateLimitedException) {
                    val wait = (e.retryAfterMs + 2_000L).coerceIn(5_000L, 75_000L)
                    if (waitedMs + wait > s.maxRateLimitWaitMs) break@jobLoop // the rest stay WAITING for a follow-up sync
                    waitedMs += wait
                    onProgress("Reddit asked to slow down, reading comments again in ${wait / 1000} s…")
                    sleep(wait)
                } catch (e: Exception) {
                    job.comments = CommentState.FAILED
                    commentFailures++
                    lastCommentError = e.message
                    break
                }
            }
        }
        val deferredComments = jobs.count { it.comments == CommentState.WAITING }
        if (commentFailures > 0) {
            errors += "Could not read the comments of $commentFailures post(s), will retry next sync: ${lastCommentError?.take(200)}"
        }

        val candidates = ArrayList<Candidate>()
        val linkless = ArrayList<Finding>()
        for (scan in scans) {
            val bodyHashes = scan.bodyMagnets.map { it.infoHash }.toHashSet()
            val fromComments = scan.commentMagnets.distinctBy { it.first.infoHash }.filter { it.first.infoHash !in bodyHashes }
            val total = scan.bodyMagnets.size + fromComments.size
            for (m in scan.bodyMagnets) candidates += candidate(scan.post, scan.parsed, m, useContext = total > 1, author = scan.post.author)
            // A comment is specific to its link, so its text always describes it.
            for ((m, author) in fromComments) candidates += candidate(scan.post, scan.parsed, m, useContext = true, author = author ?: scan.post.author)
            if (total == 0) {
                val why = when (scan.comments) {
                    CommentState.READ -> "No magnet link in the post or its comments yet"
                    CommentState.WAITING -> "Comments not read yet (Reddit limit), retrying automatically"
                    CommentState.FAILED -> "Could not read the comments"
                    CommentState.NOT_NEEDED ->
                        if (scan.post.id in commentsChecked) "No magnet link yet (comments checked recently)"
                        else "No magnet link in the post"
                }
                linkless += Finding(scan.post.createdMillis, "$why · ${short(scan.post.title)}")
            }
        }
        log("${candidates.size} magnet candidates from $f1Posts F1 posts")

        val known = catalog.entries.map { it.hash }.toHashSet()
        val ordered = candidates.sortedByDescending { it.post.createdMillis }.distinctBy { it.hash }
        val outcome = HashMap<String, String>()
        val fresh = ArrayList<Candidate>()
        for (c in ordered) {
            val reason = when {
                c.hash in known -> "already in the library"
                c.hash in catalog.ignoredHashes -> "not added: you removed it earlier"
                !(s.qualityPolicy.allows(c.quality) || (s.includeUnknownQuality && c.quality == Quality.UNKNOWN)) ->
                    "not added: ${if (c.quality == Quality.UNKNOWN) "no resolution in the title" else c.quality.label} (quality setting)"
                c.primarySession !in s.enabledSessions -> "not added: ${c.primarySession.label} is switched off in Settings"
                else -> null
            }
            if (reason != null) outcome[c.hash] = reason else fresh += c
        }
        val afterPolicy = applyPolicy(fresh, catalog.entries, s.qualityPolicy)
        val afterPolicySet = afterPolicy.map { it.hash }.toHashSet()
        fresh.filter { it.hash !in afterPolicySet }.forEach { outcome[it.hash] = "not added: a 4K version of this session is preferred" }
        val toAdd = afterPolicy.take(s.maxNewPerSync)
        afterPolicy.drop(s.maxNewPerSync).forEach { outcome[it.hash] = "waiting: too many new releases at once, next sync adds it" }

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
                outcome[c.hash] = if (entry.rdError != null) "added, but Real-Debrid failed: ${entry.rdError}" else "added"
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

        val findings = (
            ordered.map { c -> Finding(c.post.createdMillis, "${c.label} — ${outcome[c.hash] ?: "?"} · ${short(c.post.title)}") } + linkless
            ).sortedByDescending { it.at }.map { it.text }.take(80)
        val report = SyncReport(posts.size, f1Posts, candidates.size, added, updated, errors, clock() - startedAt, findings, deferredComments)
        val keepAfter = startedAt - 30L * DAY_MS
        catalog = catalog.copy(
            entries = catalog.entries.map { it.withDerivedLabels() },
            lastSyncAt = startedAt,
            lastSyncSummary = report.summary,
            lastSyncErrors = errors.toList(),
            lastSyncFindings = findings,
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

    private suspend fun fetchPosts(
        s: SyncSettings,
        now: Long,
        errors: MutableList<String>,
        onProgress: suspend (String) -> Unit,
    ): List<RedditPost> {
        val cutoff = now - s.lookbackDays.toLong() * DAY_MS
        val out = ArrayList<RedditPost>()
        var after: String? = null
        for (page in 0 until s.maxPages) {
            val result = try {
                if (page == 0) fetchFirstPage(s, onProgress) else reddit.fetchNew(s.subreddit, s.pageSize, after)
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

    /** The first page is the one that matters: if Reddit asks us to slow down, wait and try again. */
    private suspend fun fetchFirstPage(s: SyncSettings, onProgress: suspend (String) -> Unit): com.f1debrid.core.reddit.RedditPage {
        var waits = 0
        while (true) {
            try {
                return reddit.fetchNew(s.subreddit, s.pageSize, null)
            } catch (e: RateLimitedException) {
                if (waits >= MAX_RATE_LIMIT_WAITS) throw e
                waits++
                val wait = (e.retryAfterMs + 2_000L).coerceIn(5_000L, 75_000L)
                onProgress("Reddit asked to slow down, retrying in ${wait / 1000} s…")
                log("Rate limited on first page, waiting ${wait}ms")
                sleep(wait)
            }
        }
    }

    private class Finding(val at: Long, val text: String)

    private fun short(title: String): String = if (title.length > 70) title.take(69) + "…" else title

    /** Posts that already have links: look at the comments again while the weekend is fresh. */
    private fun shouldRecheckComments(post: RedditPost, checked: Map<String, Long>, now: Long): Boolean {
        if (post.numComments == 0) return false
        if (now - post.createdMillis > 4 * DAY_MS) return false
        val last = checked[post.id] ?: return true
        return now - last > 6 * HOUR_MS
    }

    /** Posts without a link (session threads): read their comments, and again hourly while fresh. */
    private fun shouldCheckComments(post: RedditPost, checked: Map<String, Long>, now: Long): Boolean {
        if (post.numComments == 0) return false
        val age = now - post.createdMillis
        if (age > 7 * DAY_MS) return false
        val last = checked[post.id] ?: return true
        val recheckAfter = if (age < 3 * DAY_MS) HOUR_MS else 12 * HOUR_MS
        return now - last > recheckAfter
    }

    private enum class CommentState { NOT_NEEDED, WAITING, READ, FAILED }

    private class PostScan(val post: RedditPost, val parsed: ParsedTitle, val bodyMagnets: List<MagnetLink>) {
        val commentMagnets = ArrayList<Pair<MagnetLink, String?>>()
        var comments = CommentState.NOT_NEEDED
    }

    private fun candidate(post: RedditPost, title: ParsedTitle, magnet: MagnetLink, useContext: Boolean, author: String?): Candidate {
        val dn = magnet.displayName?.let { F1TitleParser.parse(it) }
        val ctx = if (useContext) magnet.contextLabel?.let { F1TitleParser.parse(it) } else null
        val quality = listOfNotNull(dn?.quality, ctx?.quality, title.quality).firstOrNull { it != Quality.UNKNOWN } ?: Quality.UNKNOWN
        val sessions = listOfNotNull(dn?.sessions, ctx?.sessions, title.sessions).firstOrNull { it.isNotEmpty() } ?: emptyList()
        val year = title.year ?: dn?.year
        val round = title.round ?: dn?.round
        val gp = title.gpName ?: dn?.gpName
        val broadcaster = dn?.broadcaster ?: ctx?.broadcaster ?: title.broadcaster
        val merged = ParsedTitle(true, quality, sessions, year, round, gp, broadcaster)
        return Candidate(post, author, magnet, quality, sessions, year, round, gp, broadcaster, F1TitleParser.eventKey(merged, post.title))
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
        author = c.author,
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
        const val MAX_RATE_LIMIT_WAITS = 2
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
