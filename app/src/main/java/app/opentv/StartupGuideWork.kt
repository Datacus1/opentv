/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import kotlinx.coroutines.CancellationException

/** Optional guide work must never terminate the TV app; real cancellation still propagates. */
internal suspend fun runOptionalStartupGuideWork(
    onFailure: (Exception) -> Unit,
    work: suspend () -> Unit,
) {
    try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    }
}
