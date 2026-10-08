/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

/** Sanitized, process-wide guide state. A missing listing is not proof of a missing schedule. */
data class GuideHealth(
    val storageBlocked: Boolean = false,
    val freeBytes: Long = 0,
    val refreshing: Boolean = false,
    val failed: Boolean = false,
) {
    /** Display-only notice: never covers programmes that are already cached. */
    fun notice(): String? = when {
        storageBlocked -> "Guide updates paused by low storage — retrying automatically."
        refreshing -> "Updating guide…"
        failed -> "Guide update failed — cached listings retained; retrying automatically."
        else -> null
    }

    fun emptyListingMessage(fallback: String): String = when {
        storageBlocked -> "Listings missing; low storage has paused guide updates"
        refreshing -> "Guide updating…"
        failed -> "Listings missing; guide refresh failed — retrying automatically"
        else -> fallback
    }
}
