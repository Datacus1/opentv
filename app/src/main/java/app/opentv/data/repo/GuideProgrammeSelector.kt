package app.opentv.data.repo

import app.opentv.data.model.Programme
import java.util.PriorityQueue

/** Preflight stores identities/times, never descriptions or an entire XMLTV file. */
internal class GuideProgrammeSelector(
    private val now: Long,
    private val oldest: Long,
    private val newest: Long,
    private val limit: Int,
    maxChannels: Int,
) {
    data class Key(val channel: String, val start: Long)
    private data class Slot(val key: Key, val end: Long)
    private data class Baseline(val channel: String, var current: Slot? = null, var next: Slot? = null)
    private val channelLimit = if (limit <= 0) 0 else minOf(maxChannels, maxOf(1, limit / 2))
    private val baselines = LinkedHashMap<String, Baseline>()
    // Reserve two identities per channel before spending the budget on extra days/history.
    private val extraLimit = (limit - 2 * channelLimit).coerceAtLeast(0)
    private val ordering = compareBy<Slot> { tier(it) }
        .thenBy { if (it.key.start > now) it.key.start - now else now - it.key.start }
        .thenBy { it.key.channel }.thenBy { it.key.start }
    private val extras = PriorityQueue(ordering.reversed())
    private val extraKeys = HashSet<Key>()
    private var eligible = 0L
    private var channelOverflow = false

    private fun tier(slot: Slot): Int = when {
        slot.key.start <= now && slot.end > now -> 0
        slot.key.start > now -> 1
        else -> 2
    }

    fun offer(programme: Programme) {
        if (programme.endUtcMillis <= oldest || programme.startUtcMillis >= newest) return
        val baseline = baselines[programme.epgChannelId] ?: if (baselines.size < channelLimit) {
            Baseline(programme.epgChannelId).also { baselines[programme.epgChannelId] = it }
        } else { channelOverflow = true; return }
        // All identities reuse one bounded channel string rather than one per programme row.
        val slot = Slot(Key(baseline.channel, programme.startUtcMillis), programme.endUtcMillis)
        if (slot.key !in extraKeys && slot.key != baseline.current?.key && slot.key != baseline.next?.key) eligible++
        baseline.let {
            if (tier(slot) == 0 && (it.current == null || slot.key.start > it.current!!.key.start)) {
                it.current = slot
            } else if (tier(slot) == 1 && (it.next == null || slot.key.start < it.next!!.key.start)) {
                it.next = slot
            }
        }
        if (extraLimit > 0 && slot.key !in extraKeys) {
            if (extras.size < extraLimit) { extras.add(slot); extraKeys.add(slot.key) }
            else if (ordering.compare(slot, extras.peek()) < 0) {
                extraKeys.remove(extras.remove().key)
                extras.add(slot); extraKeys.add(slot.key)
            }
        }
    }

    fun selected(): Set<Key> = buildSet {
        baselines.values.forEach { baseline ->
            baseline.current?.let { if (size < limit) add(it.key) }
        }
        baselines.values.forEach { baseline -> baseline.next?.let { if (size < limit) add(it.key) } }
        extras.forEach { if (size < limit) add(it.key) }
    }

    fun truncated(selected: Set<Key>): Boolean = channelOverflow || eligible > selected.size
}
