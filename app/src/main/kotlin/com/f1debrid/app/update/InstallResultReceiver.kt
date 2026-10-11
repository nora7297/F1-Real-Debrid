package com.f1debrid.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import com.f1debrid.app.F1DebridApp

/** Receives the outcome of a [PackageInstaller] session started by [Updater]. */
class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.f1debrid.app.INSTALL_RESULT"
        const val EXTRA_VERSION_CODE = "version_code"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as F1DebridApp).graph.updater
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("F1Debrid", "Install status $status: $message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    updater.onInstallResult(false, "the system did not provide an install dialog")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    updater.onInstallResult(false, "could not open the install dialog: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstallResult(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallResult(false, "cancelled")
            else -> updater.onInstallResult(false, message ?: "status $status")
        }
    }
}
