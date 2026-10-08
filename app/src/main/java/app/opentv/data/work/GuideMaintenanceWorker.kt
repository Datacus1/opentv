/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.core.ServiceLocator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Idle, bounded cache maintenance must be runnable even when Android says storage is low.
 * The repository independently checks playback, recording and working-space reserves before
 * every mutation. This worker never refreshes the catalogue and never launches a TV activity.
 */
class GuideMaintenanceWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val graph = ServiceLocator.get(applicationContext)
            if (!graph.epgRepository.hasGuideSources()) return Result.success()
            val wasBlocked = graph.epgRepository.health.value.storageBlocked
            val ready = graph.epgRepository.maintainCache(System.currentTimeMillis())
            // A constrained persistent retry observes the *application* reserve, which is higher
            // than Android's storage-not-low threshold. KEEP coalesces startup/maintenance retries.
            if (wasBlocked && ready) GuideRecoveryWorker.enqueueRestoredSpace(applicationContext)
            else GuideRecoveryWorker.enqueue(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Guide maintenance deferred: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "GuideMaintenance"
        internal const val WORK_NAME = "opentv-guide-maintenance"

        internal fun request(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<GuideMaintenanceWorker>(30, TimeUnit.MINUTES)
                // Intentionally no storage/network constraint: cleanup is local, and the repo
                // is the authority on when a bounded mutation is safe.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request(),
            )
        }
    }
}

/** Guide-only recovery: failures never cause repeated full catalogue imports. */
class GuideRecoveryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val graph = ServiceLocator.get(applicationContext)
            if (!graph.epgRepository.hasGuideSources()) return Result.success()
            val summary = graph.epgRepository.syncAll(System.currentTimeMillis(), force = false)
            if (needsRetry(summary.storageDeferred || summary.retryNeeded, summary.feedsFailed)) Result.retry()
            else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Provider exception messages may contain credential-bearing URLs.
            Log.w(TAG, "Guide recovery deferred: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "GuideRecovery"
        internal const val WORK_NAME = "opentv-guide-recovery"
        internal const val RESTORED_SPACE_WORK_NAME = "opentv-guide-restored"
        internal val COALESCING_POLICY = ExistingWorkPolicy.KEEP

        internal fun needsRetry(storageDeferred: Boolean, feedsFailed: Int): Boolean =
            storageDeferred || feedsFailed > 0

        internal fun request(restoredSpace: Boolean = false): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GuideRecoveryWorker>()
                .setInitialDelay(if (restoredSpace) 0L else 15L, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                COALESCING_POLICY,
                request(),
            )
        }

        /**
         * A newly restored reserve must not wait behind hours of an older blocked retry's
         * backoff. A second unique name wakes the same guide-only worker without canceling
         * ongoing work. The repository serializes both and skips feeds already made fresh.
         */
        fun enqueueRestoredSpace(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                RESTORED_SPACE_WORK_NAME,
                COALESCING_POLICY,
                request(restoredSpace = true),
            )
        }
    }
}
