package com.f1debrid.app.sync

import android.util.Log
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
) {
    data class State(
        val running: Boolean = false,
        val message: String? = null,
        val lastReport: SyncReport? = null,
        val lastError: String? = null,
        val lastRunAt: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val mutex = Mutex()

    suspend fun runSync(reason: String, quick: Boolean = false): SyncReport? {
        if (!session.isLoggedIn) return null
        if (!mutex.tryLock()) return null
        try {
            Log.i(TAG, "Sync started ($reason)")
            _state.update { it.copy(running = true, message = "Starting sync…", lastError = null) }
            val report = engine.sync(quick = quick) { msg -> _state.update { it.copy(message = msg) } }
            _state.update { it.copy(running = false, message = null, lastReport = report, lastRunAt = System.currentTimeMillis()) }
            Log.i(TAG, "Sync finished: ${report.summary}")
            report.errors.forEach { Log.w(TAG, "Sync error: $it") }
            return report
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            _state.update {
                it.copy(running = false, message = null, lastError = e.message ?: e.toString(), lastRunAt = System.currentTimeMillis())
            }
            return null
        } finally {
            mutex.unlock()
        }
    }

    fun requestSync(reason: String, quick: Boolean = false) {
        scope.launch { runSync(reason, quick) }
    }

    /** Milliseconds since the last sync finished (or the library was last synced). */
    fun msSinceLastSync(): Long =
        System.currentTimeMillis() - maxOf(_state.value.lastRunAt, catalogStore.catalog.value.lastSyncAt)

    fun syncIfStale(maxAgeMs: Long, reason: String, quick: Boolean = false) {
        if (_state.value.running) return
        if (msSinceLastSync() > maxAgeMs) requestSync(reason, quick)
    }

    private companion object {
        const val TAG = "F1Debrid"
    }
}
