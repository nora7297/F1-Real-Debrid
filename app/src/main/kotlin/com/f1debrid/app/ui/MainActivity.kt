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
import com.f1debrid.app.ui.settings.SettingsScreen
import com.f1debrid.app.ui.theme.F1DebridTheme

class MainActivity : ComponentActivity() {
    private val graph: Graph get() = (application as F1DebridApp).graph

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
        // Refresh when the app is opened, unless a sync ran very recently.
        graph.sync.syncIfStale(maxAgeMs = 15 * 60_000L, reason = "app opened")
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
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
            screen == Screen.Settings -> SettingsScreen(graph, onBack = { screen = Screen.Home })
            else -> HomeScreen(
                graph = graph,
                onOpen = { hash -> screen = Screen.Details(hash) },
                onSettings = { screen = Screen.Settings },
            )
        }
    }
}
