package com.f1debrid.core.rd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Real-Debrid OAuth2 "device code" flow for open-source apps. The user types a short code at
 * https://real-debrid.com/device, and the app receives per-device credentials plus tokens.
 */
class RealDebridAuth(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.real-debrid.com"

        /** Public client id Real-Debrid provides for open-source applications. */
        const val OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"
        const val DEVICE_GRANT_TYPE = "http://oauth.net/grant_type/device/1.0"
    }

    @Serializable
    data class DeviceCode(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        val interval: Int = 5,
        @SerialName("expires_in") val expiresIn: Int = 600,
        @SerialName("verification_url") val verificationUrl: String = "https://real-debrid.com/device",
        @SerialName("direct_verification_url") val directVerificationUrl: String? = null,
    )

    @Serializable
    data class DeviceCredentials(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("refresh_token") val refreshToken: String? = null,
    )

    private val json = RdJson.json

    suspend fun startDeviceFlow(clientId: String = OPEN_SOURCE_CLIENT_ID): DeviceCode {
        val (code, body) = get("$baseUrl/oauth/v2/device/code?client_id=$clientId&new_credentials=yes")
        if (code !in 200..299) throw IOException("Real-Debrid device code request failed (HTTP $code)")
        return json.decodeFromString(DeviceCode.serializer(), body)
    }

    /** Null while the user has not yet entered the code. */
    suspend fun pollCredentials(deviceCode: String, clientId: String = OPEN_SOURCE_CLIENT_ID): DeviceCredentials? {
        val (code, body) = get("$baseUrl/oauth/v2/device/credentials?client_id=$clientId&code=$deviceCode")
        if (code !in 200..299) return null
        val creds = runCatching { json.decodeFromString(DeviceCredentials.serializer(), body) }.getOrNull() ?: return null
        return creds.takeIf { it.clientId.isNotBlank() && it.clientSecret.isNotBlank() }
    }

    suspend fun exchangeDeviceCode(clientId: String, clientSecret: String, deviceCode: String): TokenResponse =
        token(clientId, clientSecret, deviceCode)

    suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): TokenResponse =
        token(clientId, clientSecret, refreshToken)

    private suspend fun token(clientId: String, clientSecret: String, code: String): TokenResponse {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("grant_type", DEVICE_GRANT_TYPE)
            .build()
        val (status, body) = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$baseUrl/oauth/v2/token").post(form).build()
            http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        }
        if (status !in 200..299) {
            val err = runCatching { json.decodeFromString(RdError.serializer(), body) }.getOrNull()
            throw RdApiException(status, err?.errorCode, err?.error ?: "token_request_failed", err?.errorDetails)
        }
        return json.decodeFromString(TokenResponse.serializer(), body)
    }

    private suspend fun get(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    }
}
