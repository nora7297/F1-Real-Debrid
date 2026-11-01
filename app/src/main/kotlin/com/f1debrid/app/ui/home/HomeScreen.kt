package com.f1debrid.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.f1debrid.app.BuildConfig
import com.f1debrid.app.Graph
import com.f1debrid.app.update.UpdateState
import com.f1debrid.app.update.UpdateUi
import com.f1debrid.app.ui.common.InfoText
import com.f1debrid.app.ui.common.ProgressBar
import com.f1debrid.app.ui.common.ScreenScaffold
import com.f1debrid.app.ui.common.StatusPill
import com.f1debrid.app.ui.common.statusColor
import com.f1debrid.core.sync.CatalogEntry
import com.f1debrid.core.sync.CatalogGrouping
import com.f1debrid.core.sync.Naming
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(graph: Graph, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val catalog by graph.catalogStore.catalog.collectAsState()
    val sync by graph.sync.state.collectAsState()
    val tokens by graph.prefs.tokens.collectAsState()
    val update by graph.updater.state.collectAsState()
    val context = LocalContext.current
    val groups = remember(catalog.entries) { CatalogGrouping.group(catalog.entries) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    // Start with the remote on "Sync now", so there is always something selected.
    val syncFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!initialFocusDone) initialFocusDone = runCatching { syncFocus.requestFocus() }.isSuccess
    }

    val errorList = sync.lastReport?.errors ?: catalog.lastSyncErrors
    val firstError = sync.lastError ?: errorList.firstOrNull()
    val subtitle = buildString {
        append("v").append(BuildConfig.VERSION_NAME).append(" · ")
        if (sync.running) {
            append("Syncing…")
        } else {
            append("Last sync: ").append(Naming.relativeTime(catalog.lastSyncAt, now))
            catalog.lastSyncSummary?.let { append(" · ").append(it) }
        }
        tokens?.username?.let { append(" · Real-Debrid: ").append(it) }
    }

    ScreenScaffold(
        title = "F1 Replays",
        subtitle = subtitle,
        actions = {
            UpdateUi.buttonLabel(update)?.let { label ->
                // Never disable a button: on Android TV a disabled focused button leaves nothing focused
                // and the remote stops working. Presses while busy are ignored instead.
                Button(onClick = { UpdateUi.act(context, graph, update) }) { Text(label) }
            }
            Button(
                onClick = { if (!sync.running) graph.sync.requestSync("manual") },
                modifier = Modifier.focusRequester(syncFocus),
            ) {
                Text(if (sync.running) "Syncing…" else "Sync now")
            }
            Button(onClick = onSettings) { Text("Settings") }
        },
    ) {
        if (sync.running) {
            SyncProgressPanel(sync.message ?: "Syncing…", sync.fraction ?: 0f)
        }
        val retryAt = sync.retryAt
        if (!sync.running && retryAt != null && retryAt > now) {
            val minutes = ((retryAt - now + 59_999) / 60_000).coerceAtLeast(1)
            InfoText("Reddit asked us to wait. The app will try again by itself in about $minutes min – you can keep watching meanwhile.")
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (firstError != null && !sync.running) {
            val more = if (sync.lastError != null) 0 else errorList.size - 1
            val shown = if (firstError.length > 220) firstError.take(220) + "…" else firstError
            val suffix = if (more > 0) " (+$more more – see Settings → Last sync report)" else " (details in Settings → Last sync report)"
            InfoText("Last sync problem: $shown$suffix", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (update is UpdateState.NeedsPermission || update is UpdateState.Error) {
            InfoText(UpdateUi.statusText(update), color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (groups.isEmpty()) {
            EmptyState(syncing = sync.running, error = null)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                for (group in groups) {
                    item(key = "header:" + group.eventKey) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                        )
                    }
                    items(group.entries, key = { it.hash }) { entry ->
                        ReleaseRow(entry = entry, onClick = { onOpen(entry.hash) })
                    }
                }
            }
        }
    }
}

private var initialFocusDone = false

@Composable
private fun SyncProgressPanel(message: String, fraction: Float) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ProgressBar(fraction = fraction, modifier = Modifier.fillMaxWidth(0.6f))
    }
}

@Composable
private fun EmptyState(syncing: Boolean, error: String?) {
    Column {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = if (syncing) "Looking for F1 replays…" else "No F1 replays yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        InfoText(
            "The app checks r/MotorsportsReplays for Formula 1 posts in 1080p or 4K, adds the magnet links to your " +
                "Real-Debrid account and lists them here. It re-checks automatically in the background and every time you open it.",
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            InfoText("Last error: $error", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReleaseRow(entry: CatalogEntry, onClick: () -> Unit) {
    val details = listOfNotNull(
        Naming.statusText(entry),
        Naming.humanBytes(entry.bytes).ifBlank { null },
        entry.author?.let { "u/$it" },
    ).joinToString(" · ")
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(Naming.entryTitle(entry)) },
        supportingContent = { Text(details) },
        trailingContent = {
            StatusPill(
                text = when {
                    entry.isReady -> "Ready"
                    entry.isFailed -> "Failed"
                    else -> "Pending"
                },
                color = statusColor(entry),
            )
        },
    )
}
