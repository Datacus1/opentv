package app.opentv.player

/** Rolling live-window coordinates, never a promise of a saved recording. */
internal object LiveSeekPolicy {
    const val EDGE_MARGIN_MS = 2_000L
    const val MANUAL_SEEK_GRACE_MS = 5_000L

    fun bounds(duration: Long, defaultPosition: Long, safetyOffset: Long): LongRange? {
        if (duration <= 0) return null
        val safetyEnd = (duration - safetyOffset.coerceAtLeast(EDGE_MARGIN_MS)).coerceAtLeast(0)
        val end = if (defaultPosition >= 0) minOf(defaultPosition, safetyEnd) else safetyEnd
        if (end <= EDGE_MARGIN_MS) return null
        return EDGE_MARGIN_MS..end
    }

    fun target(position: Long, delta: Long, bounds: LongRange): Long =
        (position.coerceIn(bounds) + delta.coerceIn(-120_000L, 120_000L)).coerceIn(bounds)

    fun progress(position: Long, bounds: LongRange): Float =
        ((position.coerceIn(bounds) - bounds.first).toDouble() /
            (bounds.last - bounds.first).coerceAtLeast(1)).toFloat()

    fun liveOffset(observed: Long, duration: Long, position: Long): Long =
        if (observed >= 0) observed else (duration - position.coerceAtLeast(0)).coerceAtLeast(0)

    fun clock(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT,
            seconds / 3600, (seconds / 60) % 60, seconds % 60)
        else "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    }
}

/** Bound automatic recovery even if a provider repeatedly publishes an invalid window. */
internal class LiveWindowRecoveryGate {
    private var firstRecovery = Long.MIN_VALUE
    private var count = 0
    fun allow(now: Long): Boolean {
        if (firstRecovery == Long.MIN_VALUE || now - firstRecovery >= 30_000L) {
            firstRecovery = now
            count = 0
        }
        if (count >= 2) return false
        count++
        return true
    }
    fun reset() { firstRecovery = Long.MIN_VALUE; count = 0 }
}

data class LiveSeekPosition(
    val positionMillis: Long,
    val availableMillis: Long,
    val liveOffsetMillis: Long?,
    val progress: Float,
    val recovered: Boolean,
    val estimatedOffset: Boolean = false,
)
