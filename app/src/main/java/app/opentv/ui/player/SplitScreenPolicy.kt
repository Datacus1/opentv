/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import app.opentv.data.model.Channel
import app.opentv.data.parser.ChannelNameNormalizer

internal object SplitScreenPolicy {
    fun mayOpenSecondary(recordingCount: Int?): Boolean = recordingCount == 0

    fun restoredChannelIndex(ids: List<Long>, lastFocusedId: Long?): Int =
        ids.indexOf(lastFocusedId).takeIf { it >= 0 } ?: 0
    /** Exactly one audible pane; none when backgrounded or in Android PiP. */
    fun volumes(
        audioPane: Int,
        expandedPane: Int?,
        secondaryReady: Boolean,
        foreground: Boolean,
    ): Pair<Float, Float> {
        if (!foreground) return 0f to 0f
        val audible = expandedPane ?: audioPane
        return if (audible == 1 && secondaryReady) 0f to 1f else 1f to 0f
    }

    fun logicalKey(channel: Channel): Pair<Long, String> =
        channel.sourceId to channel.groupKey.ifBlank {
            ChannelNameNormalizer.normalize(channel.name).groupKey.ifBlank { "id:${channel.id}" }
        }

    /** Avoid opening two connections for quality variants of the same channel. */
    fun choices(channels: List<Channel>, primary: Channel?): List<Channel> {
        val primaryKey = primary?.let(::logicalKey)
        return channels.asSequence()
            .filter { !it.hidden && logicalKey(it) != primaryKey }
            .groupBy(::logicalKey)
            .values
            .map { variants ->
                // Prefer a <=1080p variant for the extra pane; do not change full-screen tuning.
                val best = variants.filter { it.qualityRank <= 300 }.maxByOrNull { it.qualityRank }
                    ?: variants.minBy { it.qualityRank }
                best.copy(favourite = variants.any { it.favourite })
            }
            .sortedWith(compareBy<Channel> { it.sortIndex }.thenBy { it.displayName })
    }
}
