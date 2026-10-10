package com.f1debrid.app.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.f1debrid.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Written by the GitHub Actions workflow next to the APK on the "latest" release. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String = "",
    val apk: String,
    val sha256: String? = null,
    val built: String? = null,
    val commit: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * In-app updates: reads version.json from the GitHub release, downloads the APK when it is newer than
 * the running build, and hands it to the system package installer.
 */
class Updater(
    private val app: Application,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    companion object {
        const val VERSION_URL = "https://github.com/nora7297/F1-Real-Debrid/releases/download/latest/version.json"
        private const val TAG = "F1Debrid"
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastCheckAt = 0L

    fun checkIfStale(maxAgeMs: Long) {
        if (System.currentTimeMillis() - lastCheckAt > maxAgeMs) check()
    }

    fun check() {
        scope.launch { checkNow() }
    }

    suspend fun checkNow() {
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        try {
            val body = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(VERSION_URL).header("Cache-Control", "no-cache").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
            }
            val info = json.decodeFromString(UpdateInfo.serializer(), body)
            lastCheckAt = System.currentTimeMillis()
            _state.value = if (info.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(info) else UpdateState.UpToDate
            Log.i(TAG, "Update check: latest build ${info.versionCode}, running ${BuildConfig.VERSION_CODE}")
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            _state.value = UpdateState.Error("Update check failed: ${e.message}")
        }
    }

    fun download(info: UpdateInfo) {
        scope.launch { downloadNow(info) }
    }

    suspend fun downloadNow(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(info, 0)
        try {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "F1-Replays-${info.versionCode}.apk")
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(info.apk).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty download")
                    val total = body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        FileOutputStream(file).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                if (total > 0) {
                                    val percent = (done * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        _state.value = UpdateState.Downloading(info, percent)
                                    }
                                }
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!info.sha256.isNullOrBlank() && !hex.equals(info.sha256, ignoreCase = true)) {
                        throw IOException("the downloaded file is corrupted (checksum mismatch)")
                    }
                }
            }
            _state.value = UpdateState.ReadyToInstall(info, file)
        } catch (e: Exception) {
            Log.w(TAG, "Update download failed", e)
            _state.value = UpdateState.Error("Download failed: ${e.message}", info)
        }
    }

    /**
     * Opens the system installer. On Android 8+ the user must first allow this app to install
     * unknown apps; in that case the matching settings page is opened and the state becomes
     * [UpdateState.NeedsPermission] so the UI can ask them to press Install again.
     */
    fun install(activityContext: Context, info: UpdateInfo, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            try {
                activityContext.startActivity(settings)
                _state.value = UpdateState.NeedsPermission(info, file)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No settings screen for unknown app sources; trying the installer directly")
            }
        }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activityContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start installer", e)
            _state.value = UpdateState.Error("Could not open the installer: ${e.message}", info)
        }
    }
}
