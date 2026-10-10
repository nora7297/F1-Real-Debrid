package com.f1debrid.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.f1debrid.app.Graph
import com.f1debrid.app.ui.common.ErrorText
import com.f1debrid.app.ui.common.InfoText
import com.f1debrid.app.ui.common.ScreenScaffold
import com.f1debrid.core.rd.RdTokens
import com.f1debrid.core.rd.RealDebridApi
import com.f1debrid.core.rd.TokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface LoginState {
    data object Starting : LoginState
    data class Code(val userCode: String, val url: String, val expiresAt: Long) : LoginState
    data object Verifying : LoginState
    data class Error(val message: String) : LoginState
}

@Composable
fun LoginScreen(graph: Graph) {
    var manual by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoginState>(LoginState.Starting) }
    var token by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var manualBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(manual, attempt) {
        if (manual) return@LaunchedEffect
        state = LoginState.Starting
        runDeviceFlow(graph) { state = it }
    }

    ScreenScaffold(
        title = "Connect Real-Debrid",
        subtitle = "F1 Replays needs access to your Real-Debrid account to add torrents and play them.",
    ) {
        if (!manual) {
            when (val s = state) {
                LoginState.Starting -> InfoText("Contacting Real-Debrid…")
                is LoginState.Code -> {
                    Text("1. On your phone or computer, open", style = MaterialTheme.typography.titleMedium)
                    Text(s.url, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("2. Log in and enter this code:", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = s.userCode,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoText("Waiting for the code to be entered… it stays valid for about ${((s.expiresAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)} minutes.")
                }
                LoginState.Verifying -> InfoText("Code accepted, finishing login…")
                is LoginState.Error -> ErrorText(s.message)
            }
            Spacer(modifier = Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { attempt++ }) { Text("Get a new code") }
                Button(onClick = { manual = true }) { Text("Use an API token instead") }
            }
        } else {
            Text("Type the private API token from real-debrid.com/apitoken", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            BasicTextField(
                value = token,
                onValueChange = { token = it.trim() },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(14.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            manualError?.let { ErrorText(it) }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            manualBusy = true
                            manualError = null
                            try {
                                val user = RealDebridApi(graph.http, StaticToken(token)).user()
                                graph.prefs.saveTokens(RdTokens(accessToken = token, username = user.username))
                                graph.sync.requestSync("login")
                            } catch (e: Exception) {
                                manualError = "Real-Debrid rejected the token: ${e.message}"
                            }
                            manualBusy = false
                        }
                    },
                    enabled = token.isNotBlank() && !manualBusy,
                ) { Text(if (manualBusy) "Checking…" else "Save token") }
                Button(onClick = { manual = false; manualError = null }) { Text("Back to code login") }
            }
        }
    }
}

private class StaticToken(private val token: String) : TokenProvider {
    override suspend fun accessToken(): String = token
    override suspend fun refreshAccessToken(): String? = null
}

private suspend fun runDeviceFlow(graph: Graph, update: (LoginState) -> Unit) {
    try {
        val code = graph.auth.startDeviceFlow()
        val expiresAt = System.currentTimeMillis() + code.expiresIn * 1000L
        update(LoginState.Code(code.userCode, code.verificationUrl, expiresAt))
        val interval = code.interval.coerceAtLeast(3) * 1000L
        while (System.currentTimeMillis() < expiresAt) {
            delay(interval)
            val creds = graph.auth.pollCredentials(code.deviceCode) ?: continue
            update(LoginState.Verifying)
            val token = graph.auth.exchangeDeviceCode(creds.clientId, creds.clientSecret, code.deviceCode)
            val tokens = RdTokens(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAt = System.currentTimeMillis() + token.expiresIn * 1000L,
                clientId = creds.clientId,
                clientSecret = creds.clientSecret,
            )
            val username = runCatching { RealDebridApi(graph.http, StaticToken(tokens.accessToken)).user().username }.getOrNull()
            graph.prefs.saveTokens(tokens.copy(username = username))
            graph.sync.requestSync("login")
            return
        }
        update(LoginState.Error("The code expired. Press \"Get a new code\" to try again."))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        update(LoginState.Error("Could not talk to Real-Debrid: ${e.message}"))
    }
}
