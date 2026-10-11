package com.f1debrid.app.update

import android.content.Context
import com.f1debrid.app.Graph

/** Text and action for the update button/row, shared by the home and settings screens. */
object UpdateUi {
    fun buttonLabel(state: UpdateState): String? = when (state) {
        is UpdateState.Available -> "Update to build ${state.info.versionCode}"
        is UpdateState.Downloading -> "Downloading ${state.percent}%"
        is UpdateState.ReadyToInstall -> "Install build ${state.info.versionCode}"
        is UpdateState.NeedsPermission -> "Install build ${state.info.versionCode}"
        is UpdateState.Installing -> "Installing…"
        is UpdateState.Installed -> "Installed – reopen the app"
        is UpdateState.Error -> if (state.info != null) "Retry update" else "Retry update check"
        else -> null
    }

    fun statusText(state: UpdateState): String = when (state) {
        UpdateState.Idle -> "Press to check GitHub for a newer build"
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "You have the latest build"
        is UpdateState.Available -> "Build ${state.info.versionCode} is available – press to download"
        is UpdateState.Downloading -> "Downloading build ${state.info.versionCode}… ${state.percent}%"
        is UpdateState.ReadyToInstall -> "Downloaded – press to install build ${state.info.versionCode}"
        is UpdateState.NeedsPermission ->
            "Allow F1 Replays to install apps in the settings screen that opened, then press Install again"
        is UpdateState.Installing -> "Installing build ${state.info.versionCode}… confirm the system dialog if it appears"
        is UpdateState.Installed -> "Build ${state.info.versionCode} installed. Close and reopen the app to use it."
        is UpdateState.Error -> state.message
    }

    fun act(context: Context, graph: Graph, state: UpdateState) {
        when (state) {
            is UpdateState.Available -> graph.updater.download(state.info)
            is UpdateState.ReadyToInstall -> graph.updater.install(context, state.info, state.file)
            is UpdateState.NeedsPermission -> graph.updater.install(context, state.info, state.file)
            is UpdateState.Error -> if (state.info != null) graph.updater.retry() else graph.updater.check()
            is UpdateState.Downloading, is UpdateState.Installing, is UpdateState.Installed, UpdateState.Checking -> Unit
            UpdateState.Idle, UpdateState.UpToDate -> graph.updater.check()
        }
    }
}
