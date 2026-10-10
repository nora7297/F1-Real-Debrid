package com.f1debrid.app

import android.app.Application
import android.util.Log
import com.f1debrid.app.data.FileCatalogStore
import com.f1debrid.app.data.Prefs
import com.f1debrid.app.data.RdSession
import com.f1debrid.app.sync.SyncCoordinator
import com.f1debrid.app.update.Updater
import com.f1debrid.core.rd.RealDebridApi
import com.f1debrid.core.rd.RealDebridAuth
import com.f1debrid.core.rd.RealDebridClient
import com.f1debrid.core.reddit.RedditClient
import com.f1debrid.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency graph; one instance per process, owned by [F1DebridApp]. */
class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build()

    val prefs = Prefs(app)
    val auth = RealDebridAuth(http)
    val session = RdSession(prefs, auth)
    val rd: RealDebridClient = RealDebridApi(http, session)
    val reddit = RedditClient(
        http,
        userAgent = "android:${BuildConfig.APPLICATION_ID}:v${BuildConfig.VERSION_NAME} (F1 replays to Real-Debrid TV app)",
        clientId = { prefs.settings.value.redditClientId },
        deviceId = prefs.redditDeviceId,
    )
    val catalogStore = FileCatalogStore(app)
    val engine = SyncEngine(
        reddit = reddit,
        rd = rd,
        store = catalogStore,
        settings = { prefs.settings.value.toSyncSettings() },
        log = { Log.d(TAG, it) },
    )
    val sync = SyncCoordinator(scope, engine, session, catalogStore)
    val updater = Updater(app, http, scope)

    companion object {
        const val TAG = "F1Debrid"
    }
}
