package com.f1debrid.app.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.f1debrid.app.Graph
import com.f1debrid.app.ui.common.ErrorText
import com.f1debrid.app.ui.common.InfoText
import com.f1debrid.app.ui.common.ScreenScaffold
import com.f1debrid.core.sync.Naming

/** Everything the last sync did, in a readable form (handy for screenshots when something is off). */
@Composable
fun SyncReportScreen(graph: Graph, onBack: () -> Unit) {
    val sync by graph.sync.state.collectAsState()
    val catalog by graph.catalogStore.catalog.collectAsState()
    val report = sync.lastReport
    val errors = report?.errors ?: catalog.lastSyncErrors
    val now = System.currentTimeMillis()

    ScreenScaffold(
        title = "Last sync report",
        subtitle = report?.summary ?: catalog.lastSyncSummary ?: "No sync has run yet",
        actions = { Button(onClick = onBack) { Text("Back") } },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item {
                InfoText(
                    "Last sync: ${Naming.relativeTime(catalog.lastSyncAt, now)}" +
                        (report?.let { " · took ${it.durationMs / 1000} s · ${it.candidates} magnet link(s) found" } ?: ""),
                )
            }
            sync.lastError?.let { item { ErrorText("Sync stopped with an error: $it") } }
            if (errors.isEmpty()) {
                item { InfoText("No problems reported.") }
            } else {
                item { SectionHeader("Problems (${errors.size})") }
                itemsIndexed(errors) { index, error ->
                    ListItem(
                        selected = false,
                        onClick = {},
                        headlineContent = { Text("${index + 1}. $error") },
                    )
                }
            }
            item { SectionHeader("Releases in the library (${catalog.entries.size})") }
            items(catalog.entries.sortedByDescending { it.postedAt }.take(60), key = { it.hash }) { entry ->
                ListItem(
                    selected = false,
                    onClick = {},
                    headlineContent = { Text(entry.title) },
                    supportingContent = {
                        Text(
                            listOfNotNull(Naming.entryTitle(entry), Naming.statusText(entry), entry.author?.let { "u/$it" })
                                .joinToString(" · "),
                        )
                    },
                )
            }
        }
    }
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
