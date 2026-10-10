package com.f1debrid.app

import android.app.Application
import com.f1debrid.app.sync.SyncScheduler

class F1DebridApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        SyncScheduler.schedule(this, graph.prefs.settings.value.syncIntervalHours)
    }
}
