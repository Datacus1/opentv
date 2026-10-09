package app.opentv.data.work

import app.opentv.data.repo.GuideCapacityDeferral as D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuideCatchUpTest {
    @Test fun `continuation runs at most four passes then requests persistent retry`() = runBlocking {
        var passes = 0; var pauses = 0
        assertTrue(runGuideCatchUp({ true }, { passes++ }, { 1 }, { 0 }, { pauses++ }))
        assertEquals(4, passes); assertEquals(4, pauses)
    }
    @Test fun `completed backlog stops without another retry`() = runBlocking {
        var remaining = 3
        assertFalse(runGuideCatchUp({ true }, { remaining-- }, { remaining }, { 0 }, {}))
        assertEquals(0, remaining)
    }
    @Test fun `playback or low storage during a pass stops before the next`() = runBlocking {
        var allowed = true; var passes = 0
        assertFalse(runGuideCatchUp({ allowed }, { passes++; allowed = false }, { 1 }, { 0 }, {}))
        assertEquals(1, passes)
    }
    @Test fun `time budget and cancellation are preserved`() = runBlocking {
        var clock = 0L; var passes = 0
        assertTrue(runGuideCatchUp({ true }, { passes++; clock += 15_000 }, { 1 }, { clock }, {}))
        assertEquals(1, passes)
        try {
            runGuideCatchUp({ true }, { throw CancellationException() }, { 1 }, { 0 }, {})
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun `capacity needs actual room not a maintenance tick or unrelated failure`() {
        val marker = D.encode(12)
        assertFalse(D.fits(marker, 90, 180, 100, 200))
        assertTrue(D.fits(marker, 88, 188, 100, 200))
        assertFalse(D.fits("Download failed (IOException); cached listings retained.", 0, 0, 100, 200))
        assertFalse(D.isDeferred("Guide waiting for idle cleanup; additional rows needed: -1"))
        assertFalse(D.isDeferred("Guide waiting for idle cleanup; additional rows needed: 9999999999999999"))
        assertTrue(D.fits(D.LEGACY, 0, 0, 100, 200))
        assertFalse(D.fits(D.encode(Int.MAX_VALUE), Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
