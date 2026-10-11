package com.f1debrid.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.f1debrid.app.BuildConfig
import com.f1debrid.app.Graph
import com.f1debrid.app.data.AppSettings
import com.f1debrid.app.data.PlayerChoice
import com.f1debrid.app.sync.SyncScheduler
import com.f1debrid.app.update.UpdateState
import com.f1debrid.app.update.UpdateUi
import com.f1debrid.app.ui.common.InfoText
import com.f1debrid.app.ui.common.ScreenScaffold
import com.f1debrid.core.model.QualityPolicy
import com.f1debrid.core.model.SessionType
import com.f1debrid.core.sync.Catalog
import kotlinx.coroutines.launch

private val LOOKBACK_OPTIONS = listOf(7, 14, 21, 30, 60)
private val INTERVAL_OPTIONS = listOf(3, 6, 12, 24)

@Composable
fun SettingsScreen(graph: Graph, onBack: () -> Unit, onOpenReport: () -> Unit = {}) {
    val settings by graph.prefs.settings.collectAsState()
    val tokens by graph.prefs.tokens.collectAsState()
    val catalog by graph.catalogStore.catalog.collectAsState()
    val update by graph.updater.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var editingClientId by remember { mutableStateOf(false) }
    var clientIdDraft by remember { mutableStateOf("") }

    fun update(transform: (AppSettings) -> AppSettings) = graph.prefs.update(transform)

    ScreenScaffold(
        title = "Settings",
        subtitle = "Changes apply from the next sync.",
        actions = { Button(onClick = onBack) { Text("Back") } },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item { SectionHeader("Updates") }
            item {
                SettingRow("Version", "F1 Replays ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", enabled = false) {}
            }
            item {
                SettingRow(
                    title = UpdateUi.buttonLabel(update) ?: "Check for updates",
                    value = UpdateUi.statusText(update),
                    enabled = update !is UpdateState.Downloading && update !is UpdateState.Installing && update != UpdateState.Checking,
                ) { UpdateUi.act(context, graph, update) }
            }
            item { SectionHeader("What to add") }
            item {
                SettingRow("Quality", settings.qualityPolicy.label) {
                    update { it.copy(qualityPolicy = next(QualityPolicy.entries, it.qualityPolicy)) }
                }
            }
            items(SessionType.entries, key = { "session-" + it.name }) { session ->
                val on = session in settings.enabledSessions
                SettingRow(
                    title = if (session == SessionType.OTHER) "Unrecognised sessions" else session.label,
                    value = if (on) "On – added automatically" else "Off",
                ) {
                    update { s ->
                        s.copy(enabledSessions = if (on) s.enabledSessions - session else s.enabledSessions + session)
                    }
                }
            }
            item {
                SettingRow(
                    "Posts without a resolution in the title",
                    if (settings.includeUnknownQuality) "Add them anyway" else "Skip them (only 1080p / 4K)",
                ) { update { it.copy(includeUnknownQuality = !it.includeUnknownQuality) } }
            }
            item {
                SettingRow(
                    "Look for links in comments",
                    if (settings.fetchComments) "On" else "Off",
                ) { update { it.copy(fetchComments = !it.fetchComments) } }
            }

            item { SectionHeader("When to sync") }
            item {
                SettingRow("Look back", "${settings.lookbackDays} days of posts") {
                    update { it.copy(lookbackDays = next(LOOKBACK_OPTIONS, it.lookbackDays)) }
                }
            }
            item {
                SettingRow("Background sync", "Every ${settings.syncIntervalHours} hours (plus every time the app opens)") {
                    update { it.copy(syncIntervalHours = next(INTERVAL_OPTIONS, it.syncIntervalHours)) }
                    SyncScheduler.schedule(context, graph.prefs.settings.value.syncIntervalHours)
                }
            }
            item { SettingRow("Subreddit", "r/${settings.subreddit}", enabled = false) {} }
            item {
                SettingRow(
                    "Reddit app client ID (optional)",
                    when {
                        editingClientId -> "Type the client ID below and press Save"
                        settings.redditClientId != null -> "Set (${settings.redditClientId!!.take(4)}…) – Reddit's official API is used first"
                        else -> "Not set. Only needed if Reddit keeps blocking the sync: create an \"installed app\" at reddit.com/prefs/apps and enter its ID here"
                    },
                ) {
                    clientIdDraft = settings.redditClientId ?: ""
                    editingClientId = !editingClientId
                }
            }
            if (editingClientId) {
                item {
                    Column {
                        BasicTextField(
                            value = clientIdDraft,
                            onValueChange = { clientIdDraft = it.trim() },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(14.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                update { it.copy(redditClientId = clientIdDraft.takeIf { d -> d.isNotBlank() }) }
                                editingClientId = false
                            }) { Text("Save") }
                            Button(onClick = {
                                update { it.copy(redditClientId = null) }
                                editingClientId = false
                            }) { Text("Clear") }
                            Button(onClick = { editingClientId = false }) { Text("Cancel") }
                        }
                    }
                }
            }

            item { SectionHeader("Playback") }
            item {
                SettingRow("Player", settings.player.label) {
                    update { it.copy(player = next(PlayerChoice.entries, it.player)) }
                }
            }

            item { SectionHeader("Account & data") }
            item {
                val problems = graph.sync.state.value.lastReport?.errors?.size ?: 0
                SettingRow(
                    "Last sync report",
                    if (problems == 0) "What the last sync did, and the titles of every release in the library"
                    else "$problems problem(s) reported – press to read them",
                ) { onOpenReport() }
            }
            item {
                SettingRow(
                    "Real-Debrid",
                    when {
                        confirmLogout -> "Press again to log out"
                        tokens?.username != null -> "Logged in as ${tokens?.username}"
                        else -> "Logged in"
                    },
                ) {
                    if (confirmLogout) {
                        graph.session.logout()
                    } else {
                        confirmLogout = true
                    }
                }
            }
            item {
                SettingRow(
                    "Reset library",
                    if (confirmReset) "Press again to forget all ${catalog.entries.size} releases (nothing is deleted from Real-Debrid)"
                    else "Forget the ${catalog.entries.size} releases listed in this app",
                ) {
                    if (confirmReset) {
                        scope.launch {
                            graph.catalogStore.save(Catalog())
                            confirmReset = false
                        }
                    } else {
                        confirmReset = true
                    }
                }
            }

            item { SectionHeader("About") }
            item {
                InfoText("Scans r/${settings.subreddit} for Formula 1 replays and adds them to Real-Debrid. Source and builds: github.com/nora7297/F1-Real-Debrid")
            }
        }
    }
}

private fun <T> next(options: List<T>, current: T): T {
    val index = options.indexOf(current)
    return options[(index + 1) % options.size]
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
    )
}
