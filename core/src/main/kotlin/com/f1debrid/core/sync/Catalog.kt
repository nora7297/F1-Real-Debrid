package com.f1debrid.core.sync

import com.f1debrid.core.model.Quality
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.parse.F1TitleParser
import com.f1debrid.core.rd.RdStatus
import com.f1debrid.core.rd.RdTorrentInfo
import kotlinx.serialization.Serializable

@Serializable
data class CatalogFile(
    val rdFileId: Int,
    val path: String,
    val bytes: Long = 0,
    /** Real-Debrid hoster link; needs /unrestrict/link before it can be played. */
    val link: String? = null,
    val selected: Boolean = true,
    val sessions: List<SessionType> = emptyList(),
    val quality: Quality = Quality.UNKNOWN,
) {
    val name: String get() = path.substringAfterLast('/')
    val isVideo: Boolean get() = Naming.isVideoFile(path)
    val primarySession: SessionType get() = SessionType.primaryOf(sessions)
    val playable: Boolean get() = link != null
}

@Serializable
data class CatalogEntry(
    val hash: String,
    val magnet: String,
    val title: String,
    val postId: String,
    val postUrl: String,
    val author: String? = null,
    val postedAt: Long = 0,
    val quality: Quality = Quality.UNKNOWN,
    val sessions: List<SessionType> = emptyList(),
    val year: Int? = null,
    val round: Int? = null,
    val gpName: String? = null,
    val broadcaster: String? = null,
    val eventKey: String,
    val rdTorrentId: String? = null,
    val rdStatus: String? = null,
    val progress: Double = 0.0,
    val rdFilename: String? = null,
    val bytes: Long = 0,
    val rdError: String? = null,
    val addAttempts: Int = 0,
    val files: List<CatalogFile> = emptyList(),
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val primarySession: SessionType get() = SessionType.primaryOf(sessions)
    val sessionLabel: String get() = SessionType.labelOf(sessions)
    val isReady: Boolean get() = rdStatus == RdStatus.DOWNLOADED && files.any { it.link != null }
    val isFailed: Boolean get() = RdStatus.isTerminalFailure(rdStatus) || (rdTorrentId == null && rdError != null)
    val groupKey: String get() = "$eventKey|$primarySession"
    val playableFiles: List<CatalogFile> get() = files.filter { it.link != null }
}

@Serializable
data class Catalog(
    val entries: List<CatalogEntry> = emptyList(),
    val lastSyncAt: Long = 0,
    val lastSyncSummary: String? = null,
    /** Post id -> when its comments were last scanned for magnet links. */
    val commentsChecked: Map<String, Long> = emptyMap(),
    /** Releases the user removed; never auto-added again. */
    val ignoredHashes: Set<String> = emptySet(),
) {
    fun find(hash: String): CatalogEntry? = entries.firstOrNull { it.hash == hash }

    fun upsert(entry: CatalogEntry): Catalog =
        if (entries.any { it.hash == entry.hash }) copy(entries = entries.map { if (it.hash == entry.hash) entry else it })
        else copy(entries = entries + entry)

    fun remove(hash: String, ignore: Boolean = true): Catalog = copy(
        entries = entries.filterNot { it.hash == hash },
        ignoredHashes = if (ignore) ignoredHashes + hash else ignoredHashes,
    )
}

interface CatalogStore {
    suspend fun load(): Catalog
    suspend fun save(catalog: Catalog)
}

class InMemoryCatalogStore(initial: Catalog = Catalog()) : CatalogStore {
    @Volatile
    var catalog: Catalog = initial
        private set

    override suspend fun load(): Catalog = catalog
    override suspend fun save(catalog: Catalog) {
        this.catalog = catalog
    }
}

/** Merge the latest /torrents/info response into an entry. */
fun CatalogEntry.withInfo(info: RdTorrentInfo, now: Long): CatalogEntry {
    val selected = info.files.filter { it.selected == 1 }
    val linkByFileId = HashMap<Int, String>()
    selected.forEachIndexed { index, f -> info.links.getOrNull(index)?.let { linkByFileId[f.id] = it } }
    val files = info.files.map { f ->
        val parsed = F1TitleParser.parse(f.path.substringAfterLast('/').substringBeforeLast('.'))
        CatalogFile(
            rdFileId = f.id,
            path = f.path,
            bytes = f.bytes,
            link = linkByFileId[f.id],
            selected = f.selected == 1,
            sessions = parsed.sessions,
            quality = parsed.quality,
        )
    }
    return copy(
        rdTorrentId = info.id,
        rdStatus = info.status,
        progress = info.progress,
        rdFilename = info.filename.ifBlank { rdFilename },
        bytes = if (info.bytes > 0) info.bytes else bytes,
        files = files,
        rdError = null,
        updatedAt = now,
    )
}

data class EventGroup(
    val eventKey: String,
    val title: String,
    val newestPostedAt: Long,
    val entries: List<CatalogEntry>,
)

object CatalogGrouping {
    fun group(entries: List<CatalogEntry>): List<EventGroup> =
        entries.groupBy { it.eventKey }.map { (key, list) ->
            val sorted = list.sortedWith(
                compareBy<CatalogEntry> { it.primarySession.order }
                    .thenByDescending { it.quality.rank }
                    .thenByDescending { it.postedAt },
            )
            val titleSource = list.firstOrNull { it.gpName != null } ?: list.first()
            EventGroup(key, Naming.eventTitle(titleSource), list.maxOf { it.postedAt }, sorted)
        }.sortedByDescending { it.newestPostedAt }
}
