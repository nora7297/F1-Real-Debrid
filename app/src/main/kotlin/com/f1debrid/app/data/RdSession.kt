package com.f1debrid.app.data

import android.util.Log
import com.f1debrid.core.rd.RdApiException
import com.f1debrid.core.rd.RdTokens
import com.f1debrid.core.rd.RealDebridAuth
import com.f1debrid.core.rd.TokenProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the Real-Debrid login alive: hands out the access token and refreshes it when it expires. */
class RdSession(private val prefs: Prefs, private val auth: RealDebridAuth) : TokenProvider {
    private val mutex = Mutex()

    val isLoggedIn: Boolean get() = prefs.tokens.value != null

    override suspend fun accessToken(): String? {
        val t = prefs.tokens.value ?: return null
        if (t.canRefresh && t.expiresAt - System.currentTimeMillis() < 120_000) {
            return refreshAccessToken() ?: t.accessToken
        }
        return t.accessToken
    }

    override suspend fun refreshAccessToken(): String? = mutex.withLock {
        val t = prefs.tokens.value ?: return@withLock null
        if (!t.canRefresh) return@withLock t.accessToken
        try {
            val r = auth.refresh(t.clientId!!, t.clientSecret!!, t.refreshToken!!)
            val updated = t.copy(
                accessToken = r.accessToken,
                refreshToken = r.refreshToken ?: t.refreshToken,
                expiresAt = System.currentTimeMillis() + r.expiresIn * 1000,
            )
            prefs.saveTokens(updated)
            updated.accessToken
        } catch (e: RdApiException) {
            Log.w("F1Debrid", "Token refresh failed: ${e.message}")
            if (e.httpCode == 400 || e.httpCode == 401 || e.httpCode == 403) {
                // Refresh token revoked: force a fresh login.
                prefs.saveTokens(null)
            }
            null
        }
    }

    fun logout() {
        prefs.saveTokens(null)
    }
}
