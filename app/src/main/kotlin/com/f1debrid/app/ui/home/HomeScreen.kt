package com.f1debrid.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val firstError = sync.lastError ?: sync.lastReport?.errors?.firstOrNull()
    val subtitle = buildString {
        append("v").append(BuildConfig.VERSION_NAME).append(" · ")
        if (sync.message != null) {
            append(sync.message)
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
                Button(
                    onClick = { UpdateUi.act(context, graph, update) },
                    enabled = update !is UpdateState.Downloading,
                ) { Text(label) }
            }
            Button(onClick = { graph.sync.requestSync("manual") }, enabled = !sync.running) {
                Text(if (sync.running) "Syncing…" else "Sync now")
            }
            Button(onClick = onSettings) { Text("Settings") }
        },
    ) {
        if (firstError != null && !sync.running) {
            val more = (sync.lastReport?.errors?.size ?: 0) - 1
            val shown = if (firstError.length > 220) firstError.take(220) + "…" else firstError
            val suffix = if (more > 0) " (+$more more – see Settings → Last sync report)" else " (details in Settings → Last sync report)"
            InfoText("Last sync problem: $shown$suffix", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (update is UpdateState.NeedsPermission || (update is UpdateState.Error && (update as UpdateState.Error).info != null)) {
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
