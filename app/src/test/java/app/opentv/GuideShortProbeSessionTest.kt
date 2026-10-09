package app.opentv

import app.opentv.data.repo.*
import app.opentv.data.remote.ShortEpgProbeCounts
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuideShortProbeSessionTest {
    @Test fun `immediate ack busy invalid tokens cancel and stale result guard`() = runTest {
        val session = GuideShortProbeSession(this, { testScheduler.currentTime }, { 123 })
        var started = false; var cancelled = false
        val probe: suspend (List<Long>, Long, (GuideShortProbeRow) -> Unit) -> Unit = { ids, time, onRow ->
            assertEquals(listOf(1L, 2L), ids); assertEquals(123L, time); started = true
            onRow(GuideShortProbeRow(1, 7, "Safe", "id", 100, ShortEpgProbeCounts()))
            try { awaitCancellation() } finally { cancelled = true }
        }
        for (raw in listOf("", "1,1", "1,2,3,4,5", "-1", "x", "1".repeat(101)))
            assertEquals("INVALID_REQUEST", session.handle("start", null, raw, probe).status)
        val first = session.handle("start", null, "1,2", probe)
        assertEquals("RUNNING", first.status); assertFalse(started)
        assertEquals("BUSY", session.handle("start", null, "1,2", probe).status)
        assertEquals("UNKNOWN_TOKEN", session.handle("poll", "wrong", null, probe).status)
        runCurrent(); assertTrue(started)
        assertEquals(1, session.handle("poll", first.token, null, probe).rows.size)
        assertEquals("CANCELLED", session.handle("cancel", first.token, null, probe).status)
        runCurrent(); assertTrue(cancelled)
        assertEquals("CANCELLED", session.handle("poll", first.token, null, probe).status)
        advanceTimeBy(300_001)
        assertEquals("EXPIRED", session.handle("poll", first.token, null, probe).status)
    }
    @Test fun `deadline is incomplete process loss unknown and complete is explicit`() = runTest {
        val session = GuideShortProbeSession(this, { testScheduler.currentTime })
        val waiting: suspend (List<Long>, Long, (GuideShortProbeRow) -> Unit) -> Unit = { _, _, _ -> awaitCancellation() }
        val first = session.handle("start", null, "1", waiting)
        runCurrent(); advanceTimeBy(45_001); runCurrent()
        assertEquals("CANCELLED_OR_TIMED_OUT", session.handle("poll", first.token, null, waiting).status)
        val restarted = GuideShortProbeSession(this)
        assertEquals("UNKNOWN_TOKEN", restarted.handle("poll", first.token, null, waiting).status)
        val done = session.handle("start", null, "1") { _, _, _ -> }
        runCurrent()
        assertEquals("COMPLETE", session.handle("poll", done.token, null, waiting).status)
    }
}
