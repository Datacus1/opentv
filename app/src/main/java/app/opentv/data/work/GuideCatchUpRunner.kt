package app.opentv.data.work

/** Testable bounds shared with the real persistent worker. Time is checked between bounded passes. */
internal suspend fun runGuideCatchUp(
    allowed: suspend () -> Boolean, pass: suspend () -> Unit, remaining: () -> Int,
    now: () -> Long, pause: suspend () -> Unit,
): Boolean {
    val started = now()
    repeat(GuideCatchUpWorker.MAX_PASSES) {
        if (!allowed()) return false
        pass()
        if (remaining() == 0) return false
        if (now() - started >= GuideCatchUpWorker.MAX_RUN_MILLIS) return true
        pause()
    }
    return true
}
