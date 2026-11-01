package com.f1debrid.app.sync

import com.f1debrid.app.diag.AppLog
import com.f1debrid.app.data.FileCatalogStore
import com.f1debrid.app.data.RdSession
import com.f1debrid.core.sync.SyncEngine
import com.f1debrid.core.sync.SyncReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Serialises sync runs (manual, on-open and background) and exposes their state to the UI. */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val engine: SyncEngine,
    private val session: RdSession,
    private val catalogStore: FileCatalogStore,
    /** Asks for another sync in a few minutes (n = how many follow-ups in a row this would be). */
    private val onFollowUp: (Int) -> Unit = {},
) {
    data class State(
        val running: Boolean = false,
        val message: String? = null,
        /** 0..1 while a sync runs, for the progress bar. */
        val fraction: Float? = null,
        /** When the automatic follow-up sync is due, if one is scheduled. */
        val retryAt: Long? = null,
        val lastReport: SyncReport? = null,
        val lastError: String? = null,
        val lastRunAt: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val mutex = Mutex()

    suspend fun runSync(reason: String, followUp: Int = 0): SyncReport? {
        if (!session.isLoggedIn) return null
        if (!mutex.tryLock()) return null
        try {
            AppLog.i("Sync started ($reason)")
            _state.update { it.copy(running = true, message = "Starting sync…", fraction = 0f, lastError = null, retryAt = null) }
            val report = engine.sync { p -> _state.update { it.copy(message = p.text, fraction = p.fraction ?: it.fraction) } }
            _state.update {
                it.copy(running = false, message = null, fraction = null, lastReport = report, lastRunAt = System.currentTimeMillis())
            }
            AppLog.i("Sync finished in ${report.durationMs / 1000} s: ${report.summary}")
            report.errors.forEach { AppLog.w("Sync problem: $it") }
            if (report.retryLater && followUp < MAX_FOLLOW_UPS) {
                AppLog.i("Reddit rate limit left work undone; automatic retry ${followUp + 1} in ${SyncScheduler.FOLLOW_UP_DELAY_MINUTES} min")
                onFollowUp(followUp + 1)
                val at = System.currentTimeMillis() + SyncScheduler.FOLLOW_UP_DELAY_MINUTES * 60_000L
                _state.update { it.copy(retryAt = at) }
            }
            return report
        } catch (e: Exception) {
            AppLog.e("Sync failed", e)
            _state.update {
                it.copy(running = false, message = null, fraction = null, lastError = e.message ?: e.toString(), lastRunAt = System.currentTimeMillis())
            }
            return null
        } finally {
            mutex.unlock()
        }
    }

    fun requestSync(reason: String) {
        scope.launch { runSync(reason) }
    }

    fun syncIfStale(maxAgeMs: Long, reason: String) {
        if (_state.value.running) return
        val catalog = catalogStore.catalog.value
        // A sync that started recently counts too, even if the app was closed before it finished.
        val last = maxOf(_state.value.lastRunAt, catalog.lastSyncAt, catalog.lastSyncAttemptAt)
        if (System.currentTimeMillis() - last > maxAgeMs) requestSync(reason)
    }

    private companion object {
        const val TAG = "F1Debrid"
        const val MAX_FOLLOW_UPS = 6
    }
}
