package com.f1debrid.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.f1debrid.app.F1DebridApp
import java.util.concurrent.TimeUnit

/** Background sync so new races are already in Real-Debrid before the app is opened. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as F1DebridApp).graph
        val report = graph.sync.runSync("background") ?: return Result.success()
        val redditDown = report.scannedPosts == 0 && report.errors.any { it.startsWith("Reddit") }
        return if (redditDown) Result.retry() else Result.success()
    }
}

object SyncScheduler {
    private const val UNIQUE_NAME = "f1debrid-periodic-sync"

    fun schedule(context: Context, intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
