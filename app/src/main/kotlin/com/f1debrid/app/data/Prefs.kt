package com.f1debrid.app.data

import android.content.Context
import android.content.SharedPreferences
import com.f1debrid.app.BuildConfig
import com.f1debrid.core.model.QualityPolicy
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.rd.RdJson
import com.f1debrid.core.rd.RdTokens
import com.f1debrid.core.sync.SyncSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PlayerChoice(val label: String) {
    BUILT_IN("Built-in player"),
    EXTERNAL("External player (Kodi, VLC, MX Player…)"),
    ASK("Ask every time"),
}

data class AppSettings(
    val qualityPolicy: QualityPolicy = QualityPolicy.PREFER_UHD,
    val enabledSessions: Set<SessionType> = SyncSettings.DEFAULT_SESSIONS,
    val lookbackDays: Int = 21,
    val syncIntervalHours: Int = 6,
    val player: PlayerChoice = PlayerChoice.BUILT_IN,
    val subreddit: String = "MotorsportsReplays",
    val fetchComments: Boolean = true,
    val includeUnknownQuality: Boolean = false,
    /** Client id of the user's own Reddit "installed app"; optional, used when Reddit blocks anonymous requests. */
    val redditClientId: String? = null,
) {
    fun toSyncSettings(): SyncSettings = SyncSettings(
        subreddit = subreddit,
        lookbackDays = lookbackDays,
        qualityPolicy = qualityPolicy,
        enabledSessions = enabledSessions,
        fetchComments = fetchComments,
        includeUnknownQuality = includeUnknownQuality,
        maxPages = if (redditClientId != null || BuildConfig.REDDIT_CLIENT_ID.isNotBlank()) 3 else 1,
    )
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("f1debrid", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings

    private val _tokens = MutableStateFlow(readTokens())
    val tokens: StateFlow<RdTokens?> = _tokens

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        sp.edit()
            .putString(KEY_QUALITY, s.qualityPolicy.name)
            .putStringSet(KEY_SESSIONS, s.enabledSessions.map { it.name }.toSet())
            .putInt(KEY_LOOKBACK, s.lookbackDays)
            .putInt(KEY_INTERVAL, s.syncIntervalHours)
            .putString(KEY_PLAYER, s.player.name)
            .putString(KEY_SUBREDDIT, s.subreddit)
            .putBoolean(KEY_COMMENTS, s.fetchComments)
            .putBoolean(KEY_UNKNOWN_QUALITY, s.includeUnknownQuality)
            .putString(KEY_REDDIT_CLIENT_ID, s.redditClientId ?: "")
            .apply()
        _settings.value = s
    }

    fun saveTokens(tokens: RdTokens?) {
        val editor = sp.edit()
        if (tokens == null) editor.remove(KEY_TOKENS)
        else editor.putString(KEY_TOKENS, RdJson.json.encodeToString(RdTokens.serializer(), tokens))
        editor.apply()
        _tokens.value = tokens
    }

    fun resumePosition(key: String): Long = sp.getLong("pos:$key", 0L)

    fun saveResumePosition(key: String, positionMs: Long) {
        sp.edit().putLong("pos:$key", positionMs).apply()
    }

    private fun readSettings(): AppSettings {
        val defaults = AppSettings()
        val sessions = sp.getStringSet(KEY_SESSIONS, null)
            ?.mapNotNull { name -> SessionType.entries.firstOrNull { it.name == name } }
            ?.toSet()
        return AppSettings(
            qualityPolicy = enumOr(sp.getString(KEY_QUALITY, null), QualityPolicy.entries, defaults.qualityPolicy),
            enabledSessions = sessions ?: defaults.enabledSessions,
            lookbackDays = sp.getInt(KEY_LOOKBACK, defaults.lookbackDays),
            syncIntervalHours = sp.getInt(KEY_INTERVAL, defaults.syncIntervalHours),
            player = enumOr(sp.getString(KEY_PLAYER, null), PlayerChoice.entries, defaults.player),
            subreddit = sp.getString(KEY_SUBREDDIT, null)?.takeIf { it.isNotBlank() } ?: defaults.subreddit,
            fetchComments = sp.getBoolean(KEY_COMMENTS, defaults.fetchComments),
            includeUnknownQuality = sp.getBoolean(KEY_UNKNOWN_QUALITY, defaults.includeUnknownQuality),
            redditClientId = sp.getString(KEY_REDDIT_CLIENT_ID, null)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Stable per-install id Reddit asks for with the installed-app grant. */
    val redditDeviceId: String
        get() {
            sp.getString(KEY_REDDIT_DEVICE_ID, null)?.let { return it }
            val id = java.util.UUID.randomUUID().toString().replace("-", "").take(30)
            sp.edit().putString(KEY_REDDIT_DEVICE_ID, id).apply()
            return id
        }

    private fun readTokens(): RdTokens? {
        val raw = sp.getString(KEY_TOKENS, null) ?: return null
        return runCatching { RdJson.json.decodeFromString(RdTokens.serializer(), raw) }.getOrNull()
    }

    private fun <E : Enum<E>> enumOr(name: String?, values: List<E>, default: E): E =
        values.firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_QUALITY = "quality_policy"
        const val KEY_SESSIONS = "sessions"
        const val KEY_LOOKBACK = "lookback_days"
        const val KEY_INTERVAL = "sync_interval_hours"
        const val KEY_PLAYER = "player"
        const val KEY_SUBREDDIT = "subreddit"
        const val KEY_COMMENTS = "fetch_comments"
        const val KEY_UNKNOWN_QUALITY = "include_unknown_quality"
        const val KEY_TOKENS = "rd_tokens"
        const val KEY_REDDIT_CLIENT_ID = "reddit_client_id"
        const val KEY_REDDIT_DEVICE_ID = "reddit_device_id"
    }
}
