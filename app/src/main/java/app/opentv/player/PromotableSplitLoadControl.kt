/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/** Keep multiview bounded; allow normal buffers on subsequent track selection/tunes after promotion. */
@OptIn(UnstableApi::class)
internal class PromotableSplitLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    startupMs: Int,
    rebufferMs: Int,
    backBufferMs: Int,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    minBufferMs, maxBufferMs, startupMs, rebufferMs,
    C.LENGTH_UNSET, false, backBufferMs, backBufferMs > 0,
) {
    @Volatile private var split = true
    fun promote() { split = false }

    override fun calculateTargetBufferBytes(trackSelectionArray: Array<out ExoTrackSelection?>): Int =
        if (split) 32 * 1024 * 1024 else super.calculateTargetBufferBytes(trackSelectionArray)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        if (split) 0L else super.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        !split && super.retainBackBufferFromKeyframe(playerId)
}
