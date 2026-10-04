/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.parser.ChannelNameNormalizer

internal object SplitScreenPolicy {
    const val CONTROLS_TIMEOUT_MS = 5_000L

    fun audioAfterSelection(oldSelection: Int, newSelection: Int, audioPane: Int): Int =
        if (oldSelection != newSelection) newSelection else audioPane

    fun acceptPaneFocus(requestedPane: Int?, focusedPane: Int): Boolean =
        requestedPane == null || requestedPane == focusedPane

    // Media3 AspectRatioFrameLayout: FIT=0, ZOOM=4. Keep this policy JVM-testable.
    fun resizeMode(fillPictures: Boolean, expanded: Boolean): Int =
        if (fillPictures && !expanded) 4 else 0

    /** Match the main guide's override/provider/matched-id and sibling-variant fallback. */
    fun currentProgrammes(
        channels: List<Channel>, programmes: List<Programme>, nowMillis: Long,
    ): Map<Pair<Long, String>, Programme> {
        val byEpgId = programmes.filter { it.isLiveAt(nowMillis) }
            .sortedWith(compareByDescending<Programme> { it.startUtcMillis }.thenBy { it.feedId })
            .groupBy { it.epgChannelId }
        return channels.filter { !it.hidden }.groupBy(::logicalKey).mapNotNull { (key, variants) ->
            variants.sortedByDescending { it.qualityRank }.asSequence()
                .flatMap { it.epgCandidates.asSequence() }
                .firstNotNullOfOrNull { byEpgId[it]?.firstOrNull() }
                ?.let { key to it }
        }.toMap()
    }

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

/** Consume the whole wake-up press, including repeats/release, so it cannot expand a picture. */
internal class SplitControlsWakeGuard {
    private var downTime: Long? = null
    fun start(time: Long) { downTime = time }
    fun consumeTail(time: Long, up: Boolean): Boolean {
        if (downTime != time) { downTime = null; return false }
        if (up) downTime = null
        return true
    }
}

/** Never activate a newly opened menu from an orphan key-up or a held repeat. */
internal class MenuSelectActivation {
    private var downTime: Long? = null
    fun activate(time: Long, up: Boolean, repeat: Int): Boolean {
        if (!up) { if (repeat == 0) downTime = time; return false }
        val paired = downTime == time
        downTime = null
        return paired
    }
}
