package com.f1debrid.core.rd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RdUser(
    val id: Long = 0,
    val username: String = "",
    val email: String? = null,
    val points: Int = 0,
    val type: String = "",
    /** Seconds of premium left. */
    val premium: Long = 0,
    val expiration: String? = null,
) {
    val isPremium: Boolean get() = type.equals("premium", ignoreCase = true) || premium > 0
}

@Serializable
data class RdTorrentSummary(
    val id: String,
    val filename: String = "",
    val hash: String = "",
    val bytes: Long = 0,
    val progress: Double = 0.0,
    val status: String = "",
    val added: String? = null,
    val links: List<String> = emptyList(),
)

@Serializable
data class RdTorrentFile(
    val id: Int,
    val path: String = "",
    val bytes: Long = 0,
    val selected: Int = 0,
)

@Serializable
data class RdTorrentInfo(
    val id: String,
    val filename: String = "",
    @SerialName("original_filename") val originalFilename: String? = null,
    val hash: String = "",
    val bytes: Long = 0,
    val progress: Double = 0.0,
    val status: String = "",
    val files: List<RdTorrentFile> = emptyList(),
    /** One download link per *selected* file, in the same order as the selected files. */
    val links: List<String> = emptyList(),
    val seeders: Int? = null,
    val speed: Long? = null,
)

@Serializable
data class RdAddResult(val id: String, val uri: String = "")

@Serializable
data class RdUnrestricted(
    val id: String = "",
    val filename: String = "",
    val mimeType: String? = null,
    val filesize: Long = 0,
    val link: String = "",
    val host: String? = null,
    /** The direct, playable URL. */
    val download: String,
    val streamable: Int = 0,
)

@Serializable
data class RdError(
    val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_details") val errorDetails: String? = null,
)

/** Everything the app needs to persist to stay logged in. */
@Serializable
data class RdTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch millis; Long.MAX_VALUE for a private API token that never expires. */
    val expiresAt: Long = Long.MAX_VALUE,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val username: String? = null,
) {
    val canRefresh: Boolean get() = refreshToken != null && clientId != null && clientSecret != null
}

object RdJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
}

object RdStatus {
    const val MAGNET_ERROR = "magnet_error"
    const val MAGNET_CONVERSION = "magnet_conversion"
    const val WAITING_FILES_SELECTION = "waiting_files_selection"
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val DOWNLOADED = "downloaded"
    const val ERROR = "error"
    const val VIRUS = "virus"
    const val COMPRESSING = "compressing"
    const val UPLOADING = "uploading"
    const val DEAD = "dead"

    /** Local-only marker: Real-Debrid no longer knows this torrent. */
    const val MISSING = "missing"

    fun isTerminalFailure(status: String?): Boolean =
        status == MAGNET_ERROR || status == ERROR || status == VIRUS || status == DEAD || status == MISSING

    /** Still moving on Real-Debrid's side; worth polling again. */
    fun isPending(status: String?): Boolean =
        status != null && status != DOWNLOADED && !isTerminalFailure(status)
}
