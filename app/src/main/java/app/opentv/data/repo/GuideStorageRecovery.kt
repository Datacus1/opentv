/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import coil.imageLoader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Best-effort cache reclamation, not permission to write below the guide's physical reserve.
 * Only the image loader's disposable cache is cleared directly. Android decides which other
 * eligible caches it may reclaim; settings, providers, recordings and the database are untouched.
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class GuideStorageRecovery(
    private val context: Context,
    private val maintenanceAllowed: suspend () -> Boolean,
    private val availableBytes: suspend () -> Long = {
        StatFs(context.dataDir.absolutePath).availableBytes
    },
    private val trimOwnCache: suspend () -> Unit = {
        // Use the owner of the journal; deleting the directory behind Coil risks corruption.
        context.imageLoader.diskCache?.clear()
    },
    private val requestPlatformSpace: suspend (Long) -> Unit = { target ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val storage = context.getSystemService(StorageManager::class.java)
            if (storage != null) {
                val uuid = storage.getUuidForPath(context.dataDir)
                val allocatable = storage.getAllocatableBytes(uuid)
                val request = platformReclaimRequest(target, allocatable)
                Log.i(TAG, "Platform cache estimate=$allocatable target=$target request=$request")
                if (request > 0L) {
                    // Request the total goal, or a smaller safe platform allowance. Even a
                    // partial cache reclamation helps; physical free space remains authoritative.
                    // This does not create a reservation file or guarantee against other writers.
                    storage.allocateBytes(uuid, request)
                }
            }
        }
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val readLastAttempt: () -> Long = {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(LAST_ATTEMPT, 0L)
    },
    private val writeLastAttempt: (Long) -> Unit = { attempt ->
        // One small timestamp is intentionally outside the guide DB/WAL, even at low space.
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putLong(LAST_ATTEMPT, attempt).apply()
    },
) {
    private val mutex = Mutex()
    private var processLastAttempt = 0L

    /** True only when a fresh physical-space measurement meets the unchanged starting reserve. */
    suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (hasReserve()) return@withLock true
            if (!isIdle()) return@withLock false

            val now = nowMillis()
            val saved = bestEffortValue { readLastAttempt() } ?: 0L
            // Once this process has attempted recovery its clock is authoritative. A future
            // persisted timestamp (clock correction), combined with a failed preference write,
            // must not bypass the cooldown on every subsequent call.
            val last = processLastAttempt.takeIf { it > 0L } ?: saved
            // A backwards wall-clock correction permits one new attempt, not indefinite lockout.
            if (last > 0L && now >= last && now - last < RETRY_INTERVAL_MILLIS) {
                return@withLock false
            }
            processLastAttempt = now
            bestEffort { writeLastAttempt(now) }
            Log.i(TAG, "Idle cache recovery started")
            bestEffort { trimOwnCache() }
            if (hasReserve()) {
                Log.i(TAG, "Own cache recovery restored reserve")
                return@withLock true
            }
            if (!isIdle()) return@withLock false
            bestEffort { requestPlatformSpace(EpgRepository.SOFT_FREE_RESERVE_BYTES) }
            hasReserve().also { ready -> Log.i(TAG, "Cache recovery reserveReady=$ready") }
        }
    }

    private suspend fun hasReserve(): Boolean =
        (bestEffortValue { availableBytes() } ?: 0L) >= EpgRepository.SOFT_FREE_RESERVE_BYTES

    private suspend fun isIdle(): Boolean = bestEffortValue { maintenanceAllowed() } == true

    private suspend fun bestEffort(work: suspend () -> Unit) {
        bestEffortValue { work() }
    }

    private suspend fun <T> bestEffortValue(work: suspend () -> T): T? = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // No exception text or stack: callers may operate around private provider configuration.
        Log.w(TAG, "Cache recovery unavailable: ${error.javaClass.simpleName}")
        null
    }

    companion object {
        internal fun platformReclaimRequest(target: Long, allocatable: Long): Long =
            minOf(target.coerceAtLeast(0L), allocatable.coerceAtLeast(0L))
        internal val RETRY_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1)
        private const val TAG = "GuideStorageRecovery"
        private const val PREFERENCES = "guide-storage-recovery"
        private const val LAST_ATTEMPT = "last-attempt-millis"
    }
}
