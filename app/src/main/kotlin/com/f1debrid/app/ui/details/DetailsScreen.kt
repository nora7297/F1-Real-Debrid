package com.f1debrid.app.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.f1debrid.app.Graph
import com.f1debrid.app.data.PlayerChoice
import com.f1debrid.app.player.Playback
import com.f1debrid.app.ui.common.ErrorText
import com.f1debrid.app.ui.common.InfoText
import com.f1debrid.app.ui.common.ScreenScaffold
import com.f1debrid.app.ui.common.StatusPill
import com.f1debrid.app.ui.common.statusColor
import com.f1debrid.app.ui.theme.ReadyGreen
import com.f1debrid.core.rd.RdStatus
import com.f1debrid.core.sync.CatalogFile
import com.f1debrid.core.sync.Naming
import kotlinx.coroutines.launch

@Composable
fun DetailsScreen(graph: Graph, hash: String, onBack: () -> Unit) {
    val catalog by graph.catalogStore.catalog.collectAsState()
    val settings by graph.prefs.settings.collectAsState()
    val entry = catalog.find(hash)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingFile by remember { mutableStateOf<CatalogFile?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    if (entry == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    fun play(file: CatalogFile, external: Boolean) {
        pendingFile = null
        scope.launch {
            busy = "Getting stream link from Real-Debrid…"
            error = null
            try {
                val url = Playback.resolve(graph, file)
                val title = "${Naming.eventTitle(entry)} – ${Naming.fileTitle(file)}"
                val launched = if (external) {
                    Playback.launchExternal(context, url, title)
                } else {
                    Playback.launchBuiltIn(context, url, title, resumeKey = "${entry.hash}:${file.rdFileId}")
                }
                if (!launched) {
                    error = "No external video player found. Install Kodi, VLC or MX Player, or switch to the built-in player in Settings."
                }
            } catch (e: Exception) {
                error = "Could not get a stream link: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun requestPlay(file: CatalogFile) {
        when (settings.player) {
            PlayerChoice.BUILT_IN -> play(file, external = false)
            PlayerChoice.EXTERNAL -> play(file, external = true)
            PlayerChoice.ASK -> pendingFile = file
        }
    }

    val playable = entry.playableFiles.sortedWith(compareBy({ it.primarySession.order }, { it.name }))
    val others = entry.files.filter { it.link == null }

    ScreenScaffold(
        title = Naming.eventTitle(entry),
        subtitle = Naming.entryTitle(entry),
        actions = { Button(onClick = onBack) { Text("Back") } },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item(key = "info") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(Naming.statusText(entry), statusColor(entry))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    InfoText("Reddit post: ${entry.title}")
                    InfoText(
                        listOfNotNull(
                            entry.author?.let { "Posted by u/$it" },
                            "Posted ${Naming.relativeTime(entry.postedAt, System.currentTimeMillis())}",
                            Naming.humanBytes(entry.bytes).ifBlank { null },
                            entry.rdFilename,
                        ).joinToString(" · "),
                    )
                    entry.rdError?.let { ErrorText(it) }
                    busy?.let { InfoText(it, color = ReadyGreen) }
                    error?.let { ErrorText(it) }

                    pendingFile?.let { file ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Play ${Naming.fileTitle(file)} with…", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { play(file, external = false) }) { Text("Built-in player") }
                            Button(onClick = { play(file, external = true) }) { Text("External player") }
                            Button(onClick = { pendingFile = null }) { Text("Cancel") }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val first = playable.firstOrNull()
                        if (first != null) {
                            Button(onClick = { requestPlay(first) }, enabled = busy == null) {
                                Text(if (playable.size == 1) "Play" else "Play ${Naming.fileTitle(first)}")
                            }
                            Button(onClick = { play(first, external = true) }, enabled = busy == null) {
                                Text("External player")
                            }
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = "Checking Real-Debrid…"
                                    error = null
                                    try {
                                        graph.engine.refreshEntry(entry.hash)
                                    } catch (e: Exception) {
                                        error = e.message
                                    }
                                    busy = null
                                }
                            },
                            enabled = busy == null,
                        ) { Text("Refresh status") }
                        if (entry.isFailed || entry.rdStatus == RdStatus.MISSING || entry.rdTorrentId == null) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = "Adding to Real-Debrid…"
                                        error = null
                                        try {
                                            graph.engine.readd(entry.hash)
                                        } catch (e: Exception) {
                                            error = e.message
                                        }
                                        busy = null
                                    }
                                },
                                enabled = busy == null,
                            ) { Text("Re-add to Real-Debrid") }
                        }
                        Button(
                            onClick = {
                                if (confirmRemove) {
                                    scope.launch {
                                        graph.catalogStore.update { it.remove(entry.hash) }
                                        onBack()
                                    }
                                } else {
                                    confirmRemove = true
                                }
                            },
                        ) { Text(if (confirmRemove) "Press again to remove" else "Remove from list") }
                    }
                }
            }

            if (playable.isNotEmpty()) {
                item(key = "files-header") { SectionHeader("Files (${playable.size})") }
                items(playable, key = { "p" + it.rdFileId }) { file ->
                    FileRow(file, enabled = busy == null, onClick = { requestPlay(file) })
                }
            }
            if (others.isNotEmpty()) {
                item(key = "others-header") { SectionHeader("Not available yet") }
                items(others, key = { "o" + it.rdFileId }) { file ->
                    FileRow(file, enabled = false, onClick = {})
                }
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

@Composable
private fun FileRow(file: CatalogFile, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        headlineContent = { Text(Naming.fileTitle(file)) },
        supportingContent = {
            Text(listOfNotNull(file.name, Naming.humanBytes(file.bytes).ifBlank { null }).joinToString(" · "))
        },
        trailingContent = {
            if (file.link != null) StatusPill("Play", ReadyGreen)
        },
    )
}
