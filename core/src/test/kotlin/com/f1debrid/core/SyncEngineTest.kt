package com.f1debrid.core

import com.f1debrid.core.magnet.Magnets
import com.f1debrid.core.model.Quality
import com.f1debrid.core.model.QualityPolicy
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.rd.RdAddResult
import com.f1debrid.core.rd.RdApiException
import com.f1debrid.core.rd.RdTorrentFile
import com.f1debrid.core.rd.RdTorrentInfo
import com.f1debrid.core.rd.RdTorrentSummary
import com.f1debrid.core.rd.RdUnrestricted
import com.f1debrid.core.rd.RdUser
import com.f1debrid.core.rd.RealDebridClient
import com.f1debrid.core.reddit.RateLimitedException
import com.f1debrid.core.reddit.RedditComment
import com.f1debrid.core.reddit.RedditPage
import com.f1debrid.core.reddit.RedditPost
import com.f1debrid.core.reddit.RedditSource
import com.f1debrid.core.sync.Catalog
import com.f1debrid.core.sync.CatalogEntry
import com.f1debrid.core.sync.InMemoryCatalogStore
import com.f1debrid.core.sync.SyncEngine
import com.f1debrid.core.sync.SyncSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
    private val now = 1_760_000_000_000L
    private val hashUhd = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val hashFhd = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val hashQuali = "cccccccccccccccccccccccccccccccccccccccc"
    private val hashFp1 = "dddddddddddddddddddddddddddddddddddddddd"
    private val hashComment = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

    private fun magnet(hash: String, dn: String? = null) = "magnet:?xt=urn:btih:$hash" + (dn?.let { "&dn=$it" } ?: "")

    private fun post(id: String, title: String, body: String, ageHours: Int = 2, comments: Int = 0) = RedditPost(
        id = id, title = title, selftext = body, createdUtc = (now - ageHours * 3_600_000L) / 1000.0,
        permalink = "/r/MotorsportsReplays/comments/$id/x/", numComments = comments, author = "poster",
    )

    private class FakeReddit(val pages: List<RedditPage>, val comments: Map<String, List<String>> = emptyMap()) : RedditSource {
        var commentFetches = 0
        var rateLimitsLeft = 0
        override suspend fun fetchNew(subreddit: String, limit: Int, after: String?): RedditPage {
            if (rateLimitsLeft > 0) {
                rateLimitsLeft--
                throw RateLimitedException(10_000)
            }
            val index = after?.toIntOrNull() ?: 0
            return pages.getOrElse(index) { RedditPage(emptyList(), null) }
        }
        var commentRateLimitsLeft = 0
        override suspend fun fetchComments(permalink: String): List<RedditComment> {
            if (commentRateLimitsLeft > 0) {
                commentRateLimitsLeft--
                throw RateLimitedException(10_000)
            }
            commentFetches++
            return (comments[permalink] ?: emptyList()).map { body ->
                // "user: text" lets a test name the commenter.
                val author = body.substringBefore(": ", "").takeIf { it.isNotEmpty() && !it.contains(' ') } ?: "uploader"
                RedditComment(author, body)
            }
        }
    }

    private class FakeRd : RealDebridClient {
        val added = ArrayList<String>()
        val selected = HashMap<String, List<Int>?>()
        val infos = HashMap<String, RdTorrentInfo>()
        var existing = ArrayList<RdTorrentSummary>()
        var nextId = 1
        var failAdds = false

        fun seed(id: String, hash: String, status: String, links: List<String> = emptyList()) {
            infos[id] = RdTorrentInfo(
                id = id, filename = "seeded", hash = hash, status = status, progress = if (status == "downloaded") 100.0 else 0.0,
                files = listOf(RdTorrentFile(1, "/F1.2026.R19.Race.2160p.mkv", 5_000_000_000L, if (links.isEmpty()) 0 else 1)),
                links = links,
            )
        }

        override suspend fun user() = RdUser(1, "tester", type = "premium", premium = 1000)
        override suspend fun torrents(page: Int, limit: Int) = if (page == 1) existing.toList() else emptyList()
        override suspend fun torrentInfo(id: String) = infos[id] ?: throw RdApiException(404, null, "unknown_ressource")
        override suspend fun addMagnet(magnet: String): RdAddResult {
            if (failAdds) throw RdApiException(503, null, "service_unavailable")
            val id = "rd${nextId++}"
            added += magnet
            val hash = Magnets.parse(magnet)!!.infoHash
            infos[id] = RdTorrentInfo(
                id = id, filename = "F1.2026.R19.United.States.Grand.Prix.Race", hash = hash, status = "waiting_files_selection",
                files = listOf(
                    RdTorrentFile(1, "/F1.2026.R19.United.States.Grand.Prix.Race.SkyF1UHD.2160p.mkv", 5_000_000_000L),
                    RdTorrentFile(2, "/readme.nfo", 100),
                    RdTorrentFile(3, "/Sample/sample.mkv", 1000),
                ),
            )
            return RdAddResult(id)
        }
        override suspend fun selectFiles(id: String, fileIds: List<Int>?) {
            selected[id] = fileIds
            val info = infos.getValue(id)
            infos[id] = info.copy(
                status = "downloaded", progress = 100.0,
                files = info.files.map { it.copy(selected = if (fileIds == null || it.id in fileIds) 1 else 0) },
                links = listOf("https://real-debrid.com/d/LINK$id"),
            )
        }
        override suspend fun deleteTorrent(id: String) { infos.remove(id) }
        override suspend fun unrestrict(link: String) = RdUnrestricted(download = "https://dl.example/$link", link = link)
    }

    private fun engine(reddit: FakeReddit, rd: FakeRd, store: InMemoryCatalogStore, settings: SyncSettings = SyncSettings()) =
        SyncEngine(reddit, rd, store, { settings }, clock = { now }, sleep = {})

    private val uhdRace = post("p1", "Formula 1 2026 Round 19 United States Grand Prix Race Sky Sports F1 UHD 2160p", magnet(hashUhd, "F1.2026.R19.Race.2160p"))
    private val fhdRace = post("p2", "Formula 1 2026 Round 19 United States Grand Prix Race Sky Sports F1 1080p", magnet(hashFhd, "F1.2026.R19.Race.1080p"), ageHours = 5)
    private val fhdQuali = post("p3", "[F1] 2026 United States GP Qualifying F1TV 1080p", magnet(hashQuali), ageHours = 30)
    private val fp1 = post("p4", "F1 2026 United States GP FP1 Sky 2160p", magnet(hashFp1), ageHours = 60)
    private val f2 = post("p5", "Formula 2 2026 Austin Feature Race F1TV 1080p", magnet("ffffffffffffffffffffffffffffffffffffffff"))
    private val old = post("p6", "F1 2026 Bahrain GP Race Sky 2160p", magnet("1234567890123456789012345678901234567890"), ageHours = 24 * 40)

    @Test
    fun prefersUhdAndSelectsOnlyVideoFiles() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace, fhdRace, fhdQuali, fp1, f2, old), null)))
        val rd = FakeRd()
        val store = InMemoryCatalogStore()
        val report = engine(reddit, rd, store).sync()

        assertEquals(2, report.added, report.errors.joinToString())
        assertTrue(report.errors.isEmpty(), report.errors.joinToString())
        val hashes = store.catalog.entries.map { it.hash }.toSet()
        assertEquals(setOf(hashUhd, hashQuali), hashes)
        assertEquals(listOf(1), rd.selected.values.first())
        val race = store.catalog.find(hashUhd)!!
        assertTrue(race.isReady)
        assertEquals("downloaded", race.rdStatus)
        assertEquals(SessionType.RACE, race.primarySession)
        assertEquals(Quality.UHD_2160, race.quality)
        assertEquals("2026-united-states", race.eventKey)
        assertEquals("https://real-debrid.com/d/LINKrd1", race.files.first { it.rdFileId == 1 }.link)
        assertNull(race.files.first { it.rdFileId == 2 }.link)
        assertEquals(1, race.playableFiles.size)
        assertEquals(now, store.catalog.lastSyncAt)
    }

    @Test
    fun bothPolicyAddsBothQualities() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace, fhdRace), null)))
        val rd = FakeRd()
        val store = InMemoryCatalogStore()
        engine(reddit, rd, store, SyncSettings(qualityPolicy = QualityPolicy.BOTH)).sync()
        assertEquals(setOf(hashUhd, hashFhd), store.catalog.entries.map { it.hash }.toSet())
    }

    @Test
    fun fhdOnlyPolicy() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace, fhdRace), null)))
        val rd = FakeRd()
        val store = InMemoryCatalogStore()
        engine(reddit, rd, store, SyncSettings(qualityPolicy = QualityPolicy.FHD_ONLY)).sync()
        assertEquals(setOf(hashFhd), store.catalog.entries.map { it.hash }.toSet())
    }

    @Test
    fun practiceCanBeEnabled() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(fp1), null)))
        val rd = FakeRd()
        val store = InMemoryCatalogStore()
        engine(reddit, rd, store, SyncSettings(enabledSessions = SyncSettings.DEFAULT_SESSIONS + SessionType.FP1)).sync()
        assertEquals(setOf(hashFp1), store.catalog.entries.map { it.hash }.toSet())
    }

    @Test
    fun findsMagnetInComments() = runTest {
        val noBody = post("p7", "Formula 1 2026 Round 19 United States Grand Prix Qualifying Sky 2160p", "Link in comments", comments = 3)
        val reddit = FakeReddit(
            listOf(RedditPage(listOf(noBody), null)),
            mapOf(noBody.permalink to listOf("here you go", "magnet:?xt=urn:btih:$hashComment&dn=F1.2026.R19.Qualifying.2160p")),
        )
        val rd = FakeRd()
        val store = InMemoryCatalogStore()
        engine(reddit, rd, store).sync()
        assertEquals(setOf(hashComment), store.catalog.entries.map { it.hash }.toSet())
        assertEquals(1, reddit.commentFetches)
        assertEquals(now, store.catalog.commentsChecked["p7"])

        // A second sync shortly after does not re-read the comments.
        engine(reddit, rd, store).sync()
        assertEquals(1, reddit.commentFetches)
    }

    @Test
    fun knownAndIgnoredHashesAreNotReAdded() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace, fhdQuali), null)))
        val rd = FakeRd()
        val existingEntry = CatalogEntry(
            hash = hashUhd, magnet = magnet(hashUhd), title = "t", postId = "p1", postUrl = "u", eventKey = "2026-united-states",
            quality = Quality.UHD_2160, sessions = listOf(SessionType.RACE), rdTorrentId = "rdX", rdStatus = "downloaded",
        )
        val store = InMemoryCatalogStore(Catalog(entries = listOf(existingEntry), ignoredHashes = setOf(hashQuali)))
        val report = engine(reddit, rd, store).sync()
        assertEquals(0, report.added)
        assertTrue(rd.added.isEmpty())
    }

    @Test
    fun reusesTorrentAlreadyOnRealDebrid() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace), null)))
        val rd = FakeRd()
        rd.existing += RdTorrentSummary(id = "old1", hash = hashUhd.uppercase(), status = "downloaded")
        rd.seed("old1", hashUhd, "downloaded", links = listOf("https://real-debrid.com/d/OLD"))
        val store = InMemoryCatalogStore()
        engine(reddit, rd, store).sync()
        assertTrue(rd.added.isEmpty())
        val entry = store.catalog.find(hashUhd)
        assertNotNull(entry)
        assertEquals("old1", entry.rdTorrentId)
        assertTrue(entry.isReady)
    }

    @Test
    fun multiMagnetPostUsesLabelsPerLink() = runTest {
        val body = "**Qualifying**\n\n${magnet(hashQuali)}\n\n**Race**\n\n${magnet(hashUhd)}"
        val pack = post("p8", "Formula 1 2026 Round 19 United States Grand Prix Sky Sports F1 UHD 2160p", body)
        val reddit = FakeReddit(listOf(RedditPage(listOf(pack), null)))
        val rd = FakeRd()
        val store = InMemoryCatalogStore()
        engine(reddit, rd, store).sync()
        assertEquals(SessionType.QUALIFYING, store.catalog.find(hashQuali)?.primarySession)
        assertEquals(SessionType.RACE, store.catalog.find(hashUhd)?.primarySession)
    }

    @Test
    fun failedAddsAreRecordedAndRetried() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace), null)))
        val rd = FakeRd().apply { failAdds = true }
        val store = InMemoryCatalogStore()
        val first = engine(reddit, rd, store).sync()
        assertEquals(1, first.errors.size)
        assertEquals(first.errors, store.catalog.lastSyncErrors)
        val entry = store.catalog.find(hashUhd)!!
        assertNull(entry.rdTorrentId)
        assertEquals(1, entry.addAttempts)
        assertTrue(entry.isFailed)

        rd.failAdds = false
        engine(reddit, rd, store).sync()
        val retried = store.catalog.find(hashUhd)!!
        assertEquals("rd1", retried.rdTorrentId)
        assertTrue(retried.isReady)
    }

    @Test
    fun pendingEntriesAreRefreshed() = runTest {
        val rd = FakeRd()
        rd.seed("dl1", hashUhd, "downloading")
        val entry = CatalogEntry(
            hash = hashUhd, magnet = magnet(hashUhd), title = "t", postId = "p1", postUrl = "u", eventKey = "2026-united-states",
            quality = Quality.UHD_2160, sessions = listOf(SessionType.RACE), rdTorrentId = "dl1", rdStatus = "downloading",
        )
        val store = InMemoryCatalogStore(Catalog(entries = listOf(entry)))
        rd.seed("dl1", hashUhd, "downloaded", links = listOf("https://real-debrid.com/d/DONE"))
        val report = engine(FakeReddit(listOf(RedditPage(emptyList(), null))), rd, store).sync()
        assertEquals(1, report.updated)
        assertTrue(store.catalog.find(hashUhd)!!.isReady)
    }

    @Test
    fun broadcasterIsFilledInFromRealDebridFileNames() = runTest {
        val entry = CatalogEntry(
            hash = hashQuali, magnet = magnet(hashQuali), title = "2026 F1 R17: Singapore Grand Prix", postId = "p9",
            postUrl = "u", eventKey = "2026-singapore", quality = Quality.UHD_2160, sessions = listOf(SessionType.SPRINT_QUALIFYING),
            rdTorrentId = "x1", rdStatus = "downloaded", rdFilename = "Formula1.2026.R17.Singapore.Sprint.Qualifying.SkyF1UHD.2160p",
        )
        val store = InMemoryCatalogStore(Catalog(entries = listOf(entry)))
        engine(FakeReddit(listOf(RedditPage(emptyList(), null))), FakeRd(), store).sync()
        assertEquals("Sky Sports F1", store.catalog.find(hashQuali)?.broadcaster)
    }

    @Test
    fun perMagnetBroadcasterBeatsThePostTitle() = runTest {
        val body = "**Sprint F1TV**\n\n${magnet(hashQuali, "F1.2026.R17.Sprint.F1TV.2160p")}\n\n" +
            "**Sprint Sky**\n\n${magnet(hashUhd, "F1.2026.R17.Sprint.SkyF1UHD.2160p")}"
        val pack = post("p10", "2026 F1 R17: Singapore Grand Prix Sky 2160p", body)
        val store = InMemoryCatalogStore()
        engine(FakeReddit(listOf(RedditPage(listOf(pack), null))), FakeRd(), store).sync()
        assertEquals("F1TV", store.catalog.find(hashQuali)?.broadcaster)
        assertEquals("Sky Sports F1", store.catalog.find(hashUhd)?.broadcaster)
    }

    @Test
    fun pagesUntilLookbackReached() = runTest {
        val page1 = RedditPage(listOf(uhdRace), "1")
        val page2 = RedditPage(listOf(fhdQuali, old), "2")
        val page3 = RedditPage(listOf(fp1), null)
        val reddit = FakeReddit(listOf(page1, page2, page3))
        val rd = FakeRd()
        val store = InMemoryCatalogStore()
        val report = engine(reddit, rd, store, SyncSettings(qualityPolicy = QualityPolicy.BOTH, maxPages = 3)).sync()
        assertEquals(2, report.scannedPosts)
    }

    @Test
    fun findingsExplainWhatHappenedToEachRelease() = runTest {
        val noLink = post("p11", "Formula 1 2026 R18 United States Grand Prix FP3 discussion", "no links here", ageHours = 1)
        val reddit = FakeReddit(listOf(RedditPage(listOf(noLink, uhdRace, fhdRace, fp1, f2), null)))
        val store = InMemoryCatalogStore()
        val report = engine(reddit, FakeRd(), store).sync()
        val f = report.findings
        assertTrue(f.any { it.startsWith("No magnet link in the post") && it.contains("FP3 discussion") }, f.joinToString("\n"))
        assertTrue(f.any { it.startsWith("Race · 4K · Sky Sports F1 — added") }, f.joinToString("\n"))
        assertTrue(f.any { it.startsWith("Race · 1080p · Sky Sports F1 — not added: a 4K version") }, f.joinToString("\n"))
        assertTrue(f.any { it.startsWith("Practice 1 · 4K · Sky Sports F1 — not added: Practice 1 is switched off") }, f.joinToString("\n"))
        assertTrue(f.none { it.contains("Formula 2") }, "non-F1 posts are not listed")
        assertEquals(f, store.catalog.lastSyncFindings)

        // Next sync: the same release is reported as already in the library.
        val again = engine(reddit, FakeRd(), store).sync()
        assertTrue(again.findings.any { it.startsWith("Race · 4K · Sky Sports F1 — already in the library") }, again.findings.joinToString("\n"))
    }

    @Test
    fun commentsOfRecentPostsWithLinksAreReadToo() = runTest {
        val thread = post(
            "p12", "2026 F1 R18: United States Grand Prix", "**Race**\n\n${magnet(hashUhd, "F1.2026.R18.Race.SkyF1UHD.2160p")}",
            ageHours = 20, comments = 4,
        )
        val reddit = FakeReddit(
            listOf(RedditPage(listOf(thread), null)),
            mapOf(thread.permalink to listOf("Quali: ${magnet(hashComment, "F1.2026.R18.Qualifying.SkyF1UHD.2160p")}")),
        )
        val store = InMemoryCatalogStore()
        engine(reddit, FakeRd(), store).sync()
        assertEquals(1, reddit.commentFetches)
        assertEquals(setOf(hashUhd, hashComment), store.catalog.entries.map { it.hash }.toSet())
        assertEquals(SessionType.QUALIFYING, store.catalog.find(hashComment)?.primarySession)

        // Not re-read again within a few hours.
        engine(reddit, FakeRd(), store).sync()
        assertEquals(1, reddit.commentFetches)
    }

    private val sessionThread = post(
        "p13", "[21:00 UTC] 2026 F1 - United States Grand Prix - Qualifying", "Post your links in the comments", ageHours = 12, comments = 6,
    )

    @Test
    fun sessionThreadLinksInCommentsAreAddedAndCreditedToTheUploader() = runTest {
        val reddit = FakeReddit(
            listOf(RedditPage(listOf(sessionThread), null)),
            mapOf(
                sessionThread.permalink to listOf(
                    "egortech: Sky UHD 2160p\n\n${magnet(hashUhd)}",
                    "smcgill1969: F1TV 1080p\n\n${magnet(hashFhd)}",
                    "someone: thanks for these!",
                ),
            ),
        )
        val store = InMemoryCatalogStore()
        val report = engine(reddit, FakeRd(), store).sync()
        assertTrue(report.errors.isEmpty(), report.errors.joinToString())
        val uhd = store.catalog.find(hashUhd)
        assertNotNull(uhd)
        assertEquals("egortech", uhd.author)
        assertEquals(Quality.UHD_2160, uhd.quality)
        assertEquals(SessionType.QUALIFYING, uhd.primarySession)
        assertEquals("2026-united-states", uhd.eventKey)
        // 4K preferred: the 1080p link from the same thread is reported but not added.
        assertNull(store.catalog.find(hashFhd))
        assertTrue(report.findings.any { it.contains("1080p") && it.contains("4K version") }, report.findings.joinToString("\n"))
    }

    @Test
    fun realCommentLayoutAddsThe4kAndExplainsTheRest() = runTest {
        val h720 = "1111111111111111111111111111111111111111"
        val h1080 = "2222222222222222222222222222222222222222"
        val h2160 = "3333333333333333333333333333333333333333"
        fun block(h: String, res: String, size: String) =
            "Formula.1.2026x98.United.States.Qualifying.F1TV.$res.SS\nSize: $size\n\n" +
                "magnet:?xt=urn:btih:${h.uppercase()}&dn=Formula.1.2026x98.United.States.Qualifying.F1TV.$res.SS&tr=udp://tracker.opentrackr.org:1337\n"
        val comment = "lonewolf_sg: " + block(h720, "720p", "2.19 GB") + block(h1080, "1080p", "3.54 GB") + block(h2160, "2160p", "8.97 GB")
        val reddit = FakeReddit(listOf(RedditPage(listOf(sessionThread), null)), mapOf(sessionThread.permalink to listOf(comment)))
        val store = InMemoryCatalogStore()
        val report = engine(reddit, FakeRd(), store).sync()
        val added = store.catalog.find(h2160)
        assertNotNull(added, report.findings.joinToString("\n"))
        assertEquals("lonewolf_sg", added.author)
        assertEquals("F1TV", added.broadcaster)
        assertEquals(SessionType.QUALIFYING, added.primarySession)
        assertEquals("2026-united-states", added.eventKey)
        assertNull(store.catalog.find(h1080))
        assertNull(store.catalog.find(h720))
        assertTrue(report.findings.any { it.startsWith("Qualifying · 1080p · F1TV — not added: a 4K version") }, report.findings.joinToString("\n"))
        assertTrue(report.findings.any { it.startsWith("Qualifying · 720p · F1TV — not added: 720p") }, report.findings.joinToString("\n"))
    }

    @Test
    fun rateLimitedCommentsAreRetriedWithinTheSync() = runTest {
        val reddit = FakeReddit(
            listOf(RedditPage(listOf(sessionThread), null)),
            mapOf(sessionThread.permalink to listOf("egortech: 2160p ${magnet(hashUhd)}")),
        ).apply { commentRateLimitsLeft = 2 }
        val store = InMemoryCatalogStore()
        val report = engine(reddit, FakeRd(), store).sync()
        assertTrue(report.errors.isEmpty(), report.errors.joinToString())
        assertEquals(0, report.deferredComments)
        assertNotNull(store.catalog.find(hashUhd))
    }

    @Test
    fun persistentRateLimitDefersCommentsWithoutAnError() = runTest {
        val reddit = FakeReddit(
            listOf(RedditPage(listOf(sessionThread), null)),
            mapOf(sessionThread.permalink to listOf("egortech: 2160p ${magnet(hashUhd)}")),
        ).apply { commentRateLimitsLeft = 1_000 }
        val store = InMemoryCatalogStore()
        val report = engine(reddit, FakeRd(), store).sync()
        assertTrue(report.errors.isEmpty(), report.errors.joinToString())
        assertEquals(1, report.deferredComments)
        assertTrue(report.summary.contains("1 thread(s) to read on the next pass"), report.summary)
        assertTrue(report.findings.any { it.startsWith("Comments not read yet") }, report.findings.joinToString("\n"))
        assertNull(store.catalog.commentsChecked["p13"], "a deferred thread must be read again next time")

        // The follow-up pass reads it.
        reddit.commentRateLimitsLeft = 0
        val next = engine(reddit, FakeRd(), store).sync()
        assertEquals(0, next.deferredComments)
        assertNotNull(store.catalog.find(hashUhd))
    }

    @Test
    fun readsOnlyTheNewestPageByDefault() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace), "1"), RedditPage(listOf(fhdQuali), null)))
        val report = engine(reddit, FakeRd(), InMemoryCatalogStore(), SyncSettings(qualityPolicy = QualityPolicy.BOTH)).sync()
        assertEquals(1, report.scannedPosts)
    }

    @Test
    fun waitsOutARateLimitOnTheFirstPage() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace), null))).apply { rateLimitsLeft = 2 }
        val store = InMemoryCatalogStore()
        val report = engine(reddit, FakeRd(), store).sync()
        assertTrue(report.errors.isEmpty(), report.errors.joinToString())
        assertEquals(1, report.scannedPosts)
        assertEquals(setOf(hashUhd), store.catalog.entries.map { it.hash }.toSet())
    }

    @Test
    fun givesUpAfterRepeatedRateLimitsWithAHint() = runTest {
        val reddit = FakeReddit(listOf(RedditPage(listOf(uhdRace), null))).apply { rateLimitsLeft = 5 }
        val report = engine(reddit, FakeRd(), InMemoryCatalogStore()).sync()
        assertEquals(0, report.scannedPosts)
        assertEquals(1, report.errors.size)
        assertTrue(report.errors[0].contains("client ID"), report.errors[0])
    }
}
