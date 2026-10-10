package com.f1debrid.core.rd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Supplies (and refreshes) the bearer token used for Real-Debrid calls. */
interface TokenProvider {
    suspend fun accessToken(): String?

    /** Returns a fresh token, or null when a refresh is impossible (user must log in again). */
    suspend fun refreshAccessToken(): String?
}

class RdApiException(
    val httpCode: Int,
    val errorCode: Int?,
    val error: String?,
    val details: String? = null,
) : IOException(describe(httpCode, errorCode, error, details)) {
    val isAuthError: Boolean get() = httpCode == 401 || errorCode == 8
    val isPermissionError: Boolean get() = httpCode == 403 || errorCode == 9

    companion object {
        private fun describe(code: Int, errorCode: Int?, error: String?, details: String?): String {
            val base = when {
                !error.isNullOrBlank() -> error.replace('_', ' ')
                code == 401 -> "not authorised"
                code == 403 -> "permission denied"
                code == 404 -> "not found"
                code == 429 -> "too many requests"
                code >= 500 -> "Real-Debrid server error"
                else -> "request failed"
            }
            val suffix = buildString {
                if (!details.isNullOrBlank()) append(" ($details)")
                append(" [HTTP ").append(code)
                if (errorCode != null) append(", code ").append(errorCode)
                append("]")
            }
            return base + suffix
        }
    }
}

interface RealDebridClient {
    suspend fun user(): RdUser
    suspend fun torrents(page: Int = 1, limit: Int = 100): List<RdTorrentSummary>
    suspend fun torrentInfo(id: String): RdTorrentInfo
    suspend fun addMagnet(magnet: String): RdAddResult

    /** @param fileIds the file ids to download, or null for all files. */
    suspend fun selectFiles(id: String, fileIds: List<Int>?)
    suspend fun deleteTorrent(id: String)
    suspend fun unrestrict(link: String): RdUnrestricted
}

/**
 * Thin client for the Real-Debrid REST API (https://api.real-debrid.com/rest/1.0).
 * Retries once with a refreshed token on 401 and backs off briefly on 429.
 */
class RealDebridApi(
    private val http: OkHttpClient,
    private val tokens: TokenProvider,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : RealDebridClient {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.real-debrid.com/rest/1.0"
    }

    private val json = RdJson.json

    override suspend fun user(): RdUser =
        request("GET", "/user") { json.decodeFromString(RdUser.serializer(), it) }

    override suspend fun torrents(page: Int, limit: Int): List<RdTorrentSummary> =
        request("GET", "/torrents?page=$page&limit=$limit") { body ->
            if (body.isBlank()) emptyList() else json.decodeFromString(ListSerializer(RdTorrentSummary.serializer()), body)
        }

    override suspend fun torrentInfo(id: String): RdTorrentInfo =
        request("GET", "/torrents/info/$id") { json.decodeFromString(RdTorrentInfo.serializer(), it) }

    override suspend fun addMagnet(magnet: String): RdAddResult =
        request("POST", "/torrents/addMagnet", mapOf("magnet" to magnet)) { json.decodeFromString(RdAddResult.serializer(), it) }

    override suspend fun selectFiles(id: String, fileIds: List<Int>?) {
        val files = fileIds?.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "all"
        request("POST", "/torrents/selectFiles/$id", mapOf("files" to files)) { }
    }

    override suspend fun deleteTorrent(id: String) {
        request("DELETE", "/torrents/delete/$id") { }
    }

    override suspend fun unrestrict(link: String): RdUnrestricted =
        request("POST", "/unrestrict/link", mapOf("link" to link)) { json.decodeFromString(RdUnrestricted.serializer(), it) }

    private class HttpResult(val code: Int, val body: String)

    private suspend fun <T> request(
        method: String,
        path: String,
        form: Map<String, String>? = null,
        parse: (String) -> T,
    ): T {
        var token = tokens.accessToken() ?: throw RdApiException(401, 8, "not_logged_in", "No Real-Debrid token saved")
        var attempt = 0
        while (true) {
            attempt++
            val result = execute(method, path, form, token)
            if (result.code in 200..299) return parse(result.body)
            val err = runCatching { json.decodeFromString(RdError.serializer(), result.body) }.getOrNull()
            val ex = RdApiException(result.code, err?.errorCode, err?.error, err?.errorDetails)
            when {
                ex.isAuthError && attempt == 1 -> token = tokens.refreshAccessToken() ?: throw ex
                result.code == 429 && attempt < 4 -> delay(1500L * attempt)
                result.code >= 500 && attempt < 3 -> delay(1000L * attempt)
                else -> throw ex
            }
        }
    }

    private suspend fun execute(method: String, path: String, form: Map<String, String>?, token: String): HttpResult =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                else -> {
                    val body = FormBody.Builder()
                    form?.forEach { (k, v) -> body.add(k, v) }
                    builder.method(method, body.build())
                }
            }
            http.newCall(builder.build()).execute().use { resp ->
                HttpResult(resp.code, resp.body?.string().orEmpty())
            }
        }
}
