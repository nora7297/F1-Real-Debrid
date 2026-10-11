package com.f1debrid.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.f1debrid.app.F1DebridApp
import com.f1debrid.app.Graph
import com.f1debrid.app.ui.details.DetailsScreen
import com.f1debrid.app.ui.home.HomeScreen
import com.f1debrid.app.ui.login.LoginScreen
import com.f1debrid.app.ui.report.SyncReportScreen
import com.f1debrid.app.ui.settings.SettingsScreen
import com.f1debrid.app.ui.theme.F1DebridTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph: Graph get() = (application as F1DebridApp).graph
    private var autoRefresh: Job? = null

    private companion object {
        /** How often the library refreshes while the app is on screen. */
        const val AUTO_REFRESH_MS = 2 * 60_000L

        /** After this long without a sync, opening the app runs a full (multi-page) sync. */
        const val FULL_SYNC_AFTER_MS = 6 * 3_600_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            F1DebridTheme {
                AppRoot(graph)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh when the app is opened, unless a sync ran in the last couple of minutes.
        val quick = graph.sync.msSinceLastSync() < FULL_SYNC_AFTER_MS
        graph.sync.syncIfStale(maxAgeMs = AUTO_REFRESH_MS, reason = "app opened", quick = quick)
        graph.updater.checkIfStale(maxAgeMs = 10 * 60_000L)

        // Keep refreshing while the app is on screen; stops when it is paused (e.g. a video is playing).
        autoRefresh?.cancel()
        autoRefresh = graph.scope.launch {
            while (isActive) {
                delay(20_000)
                graph.sync.syncIfStale(maxAgeMs = AUTO_REFRESH_MS, reason = "auto-refresh", quick = true)
            }
        }
    }

    override fun onPause() {
        autoRefresh?.cancel()
        autoRefresh = null
        super.onPause()
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Report : Screen
    data class Details(val hash: String) : Screen
}

@Composable
fun AppRoot(graph: Graph) {
    val tokens by graph.prefs.tokens.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val loggedIn = tokens != null

    BackHandler(enabled = loggedIn && screen != Screen.Home) { screen = Screen.Home }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            !loggedIn -> LoginScreen(graph)
            screen is Screen.Details -> DetailsScreen(graph, (screen as Screen.Details).hash, onBack = { screen = Screen.Home })
            screen == Screen.Settings -> SettingsScreen(graph, onBack = { screen = Screen.Home }, onOpenReport = { screen = Screen.Report })
            screen == Screen.Report -> SyncReportScreen(graph, onBack = { screen = Screen.Settings })
            else -> HomeScreen(
                graph = graph,
                onOpen = { hash -> screen = Screen.Details(hash) },
                onSettings = { screen = Screen.Settings },
            )
        }
    }
}
