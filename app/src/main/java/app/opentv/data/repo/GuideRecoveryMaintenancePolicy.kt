/* Copyright (C) 2026 The OpenTV Contributors. GPL v3.0 or later. */
package app.opentv.data.repo

/** Small expired-only retirement is not a full-download storage reservation. */
object GuideRecoveryMaintenancePolicy {
    const val HEADROOM_BYTES = 32L * 1024 * 1024
    const val BATCH_ROWS = 100
    const val MAX_BATCHES = 4
    fun mayStart(freeBytes: Long, idle: Boolean): Boolean =
        idle && freeBytes >= EpgRepository.HARD_FREE_FLOOR_BYTES + HEADROOM_BYTES

    fun mayCheckpoint(freeBytes: Long, logicalBytes: Long, fileBytes: Long): Boolean {
        if (logicalBytes < 0 || fileBytes < 0 || !mayStart(freeBytes, true)) return false
        val growth = if (logicalBytes > fileBytes) logicalBytes - fileBytes else 0L
        return growth <= freeBytes - EpgRepository.HARD_FREE_FLOOR_BYTES - HEADROOM_BYTES
    }
}
