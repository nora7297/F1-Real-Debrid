package com.f1debrid.app.ui.logs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.f1debrid.app.Graph
import com.f1debrid.app.diag.AppLog
import com.f1debrid.app.diag.Qr
import com.f1debrid.app.ui.common.InfoText
import com.f1debrid.app.ui.common.ScreenScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → Logs: the latest crash and recent activity, viewable on the TV or as a QR code for a phone. */
@Composable
fun LogsScreen(graph: Graph, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val crashes = remember(refresh) { AppLog.crashes() }
    val lines = remember(refresh) { AppLog.recentLines(300) }
    var showQr by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { graph.prefs.markCrashesSeen() }

    if (showQr) {
        QrView(text = remember(refresh) { AppLog.shareText() }, onClose = { showQr = false })
        return
    }

    ScreenScaffold(
        title = "Logs",
        subtitle = AppLog.deviceSummary(),
        actions = {
            Button(onClick = { showQr = true }) { Text("Show as QR code") }
            Button(onClick = {
                if (confirmClear) {
                    AppLog.clear()
                    confirmClear = false
                    refresh++
                } else {
                    confirmClear = true
                }
            }) { Text(if (confirmClear) "Press again to clear" else "Clear") }
            Button(onClick = onBack) { Text("Back") }
        },
    ) {
        InfoText(
            "If something goes wrong, press \"Show as QR code\" and scan it with your phone's camera, " +
                "or take a photo of this screen, and send it over.",
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            val latest = crashes.firstOrNull()
            if (latest != null) {
                item(key = "crash-header") {
                    Header("Last crash · ${stamp(latest.at)}" + if (crashes.size > 1) " (${crashes.size} saved)" else "")
                }
                itemsIndexed(latest.summary.lines().take(30), key = { i, _ -> "c$i" }) { _, line ->
                    LogLine(line, color = MaterialTheme.colorScheme.error)
                }
            } else {
                item(key = "no-crash") { Header("No crashes recorded") }
            }
            item(key = "log-header") { Header("Recent activity, newest first (${lines.size})") }
            if (lines.isEmpty()) {
                item(key = "empty") { LogLine("Nothing logged yet.") }
            }
            itemsIndexed(lines, key = { i, _ -> "l$i" }) { _, line ->
                LogLine(line, color = if (" E " in line || " W " in line) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private fun stamp(at: Long): String = SimpleDateFormat("dd MMM HH:mm", Locale.UK).format(Date(at))

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** A focusable line, so the remote can scroll through the log. */
@Composable
private fun LogLine(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun QrView(text: String, onClose: () -> Unit) {
    val bitmap = remember(text) { runCatching { Qr.bitmap(text).asImageBitmap() }.getOrNull() }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "QR code with the app's logs",
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .size(460.dp)
                    .background(Color.White)
                    .padding(8.dp),
            )
        } else {
            Text("Could not make a QR code.", color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.width(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan with your phone", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            InfoText("Point your phone's camera at the code. It contains the device, the latest crash and the most recent log lines as plain text – copy it and paste it into the chat.")
            InfoText("No passwords or Real-Debrid tokens are included.")
            Button(onClick = onClose) { Text("Back to logs") }
        }
    }
}
