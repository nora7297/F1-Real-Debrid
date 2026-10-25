package com.f1debrid.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.f1debrid.app.F1DebridApp
import java.util.concurrent.TimeUnit

/** Background sync so new races are already in Real-Debrid before the app is opened. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_FOLLOW_UP = "follow_up"
    }

    override suspend fun doWork(): Result {
        val graph = (applicationContext as F1DebridApp).graph
        val followUp = inputData.getInt(KEY_FOLLOW_UP, 0)
        val reason = if (followUp > 0) "follow-up $followUp" else "background"
        val report = graph.sync.runSync(reason, followUp) ?: return Result.success()
        val redditDown = report.scannedPosts == 0 && report.errors.any { it.startsWith("Reddit") }
        return if (redditDown) Result.retry() else Result.success()
    }
}

object SyncScheduler {
    private const val UNIQUE_NAME = "f1debrid-periodic-sync"
    private const val FOLLOW_UP_NAME = "f1debrid-follow-up-sync"

    /** One more sync in a few minutes, to read comment threads Reddit's rate limit held back. */
    fun scheduleFollowUp(context: Context, followUp: Int, delayMinutes: Long = 5) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putInt(SyncWorker.KEY_FOLLOW_UP, followUp).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // APPEND_OR_REPLACE: when called from inside a running follow-up, the next one queues behind it.
        WorkManager.getInstance(context).enqueueUniqueWork(FOLLOW_UP_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun schedule(context: Context, intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
