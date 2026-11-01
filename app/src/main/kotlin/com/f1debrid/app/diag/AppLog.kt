package com.f1debrid.app.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.f1debrid.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own log: a small rolling file of what happened (syncs, updates, playback errors) plus a
 * saved report for every crash, both shown under Settings → Logs. Never logs tokens.
 */
object AppLog {
    private const val TAG = "F1Debrid"
    private const val MAX_BYTES = 200 * 1024L
    private const val MAX_CRASHES = 5

    private val lock = Any()
    private var dir: File? = null
    private val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK)

    data class Crash(val at: Long, val text: String) {
        /** First line of the error and the first lines of the app's own code in the trace. */
        val summary: String
            get() {
                val lines = text.lines()
                val start = lines.indexOfFirst { it.startsWith("Error:") }.coerceAtLeast(0)
                return lines.drop(start).take(40).joinToString("\n")
            }
    }

    fun init(context: Context) {
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        File(d, "crashes").mkdirs()
        dir = d
        installCrashHandler(context.applicationContext)
        i("App started · ${deviceSummary()}")
        recordPreviousExit(context.applicationContext)
    }

    fun i(message: String) = write(Log.INFO, "I", message, null)
    fun w(message: String, error: Throwable? = null) = write(Log.WARN, "W", message, error)
    fun e(message: String, error: Throwable? = null) = write(Log.ERROR, "E", message, error)

    fun deviceSummary(): String {
        val fire = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        return "F1 Replays ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}" + (if (fire) " (Fire TV)" else "") +
            " · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    /** Newest first. */
    fun recentLines(max: Int = 300): List<String> = synchronized(lock) {
        val d = dir ?: return emptyList()
        val lines = listOf(File(d, "app.log.1"), File(d, "app.log"))
            .filter { it.exists() }
            .flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
        lines.takeLast(max).asReversed()
    }

    /** Newest first. */
    fun crashes(): List<Crash> {
        val d = dir ?: return emptyList()
        return File(d, "crashes").listFiles { f -> f.name.startsWith("crash-") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f -> runCatching { Crash(f.lastModified(), f.readText()) }.getOrNull() }
    }

    fun clear() = synchronized(lock) {
        val d = dir ?: return
        File(d, "app.log").delete()
        File(d, "app.log.1").delete()
        File(d, "crashes").listFiles().orEmpty().forEach { it.delete() }
    }

    /** A short text for a QR code: device, latest crash (if any) and the most recent log lines. */
    fun shareText(maxChars: Int = 1_200): String {
        val sb = StringBuilder()
        sb.append(deviceSummary()).append('\n')
        crashes().firstOrNull()?.let { c ->
            sb.append("CRASH ").append(time.format(Date(c.at))).append('\n').append(c.summary).append('\n')
        }
        sb.append("LOG\n")
        for (line in recentLines(60)) {
            if (sb.length + line.length + 1 > maxChars) break
            sb.append(line).append('\n')
        }
        return sb.toString().take(maxChars)
    }

    private fun write(priority: Int, level: String, message: String, error: Throwable?) {
        Log.println(priority, TAG, if (error != null) message + "\n" + Log.getStackTraceString(error) else message)
        val d = dir ?: return
        val line = buildString {
            append(time.format(Date())).append(' ').append(level).append(' ').append(message.replace('\n', ' '))
            if (error != null) append(" – ").append(error.javaClass.simpleName).append(": ").append(error.message)
        }
        synchronized(lock) {
            runCatching {
                val file = File(d, "app.log")
                if (file.length() > MAX_BYTES) {
                    val old = File(d, "app.log.1")
                    old.delete()
                    file.renameTo(old)
                }
                File(d, "app.log").appendText(line + "\n")
            }
        }
    }

    private fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { saveCrash(thread.name, error, null) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun saveCrash(threadName: String, error: Throwable?, extra: String?) {
        val d = dir ?: return
        val crashDir = File(d, "crashes").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val report = buildString {
            append("Time: ").append(time.format(Date(now))).append('\n')
            append("Device: ").append(deviceSummary()).append('\n')
            append("Thread: ").append(threadName).append('\n')
            if (error != null) {
                append("Error: ").append(error.javaClass.name).append(": ").append(error.message).append('\n')
                append(Log.getStackTraceString(error).lines().take(80).joinToString("\n")).append('\n')
            }
            if (extra != null) append(extra).append('\n')
            append("\nRecent log:\n")
            recentLines(30).asReversed().forEach { append(it).append('\n') }
        }
        File(crashDir, "crash-$now.txt").writeText(report)
        write(Log.ERROR, "E", "CRASH on $threadName: ${error?.javaClass?.simpleName}: ${error?.message ?: extra?.lineSequence()?.firstOrNull()}", null)
        crashDir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MAX_CRASHES).forEach { it.delete() }
    }

    /**
     * Android 11+ remembers why the previous run of the app ended. Freezes (ANR), native crashes and
     * being killed for memory never reach the crash handler, so record those here on the next start.
     */
    private fun recordPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences("f1debrid-diag", Context.MODE_PRIVATE)
            val seen = prefs.getLong("last_exit_seen", 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).filter { it.timestamp > seen }
            for (exit in exits.sortedBy { it.timestamp }) {
                val reason = when (exit.reason) {
                    ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
                    ApplicationExitInfo.REASON_CRASH -> "crashed"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "was closed by the system for low memory"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "was closed for using too much"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                    else -> null
                } ?: continue
                val detail = "Previous run (" + time.format(Date(exit.timestamp)) + ") " + reason + ": " + (exit.description ?: "no details")
                if (exit.reason == ApplicationExitInfo.REASON_ANR || exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    val trace = runCatching {
                        exit.traceInputStream?.bufferedReader()?.useLines { lines -> lines.take(60).joinToString("\n") }
                    }.getOrNull()
                    saveCrash("previous run", null, detail + (trace?.let { "\n$it" } ?: ""))
                } else {
                    w(detail)
                }
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit_seen", it).apply() }
        }.onFailure { w("Could not read why the previous run ended", it) }
    }
}
