package app.opentv

import app.opentv.data.repo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GuideFeedProbeSessionTest {
    private suspend fun fixture(): GuideFeedProbeEvidence {
        val report = GuideFeedDiagnostic.scan("<tv><channel id=\"a\"/></tv>".byteInputStream(), 1, 123)
        val row = report.rows.single()
        return GuideFeedProbeEvidence(1, 100, 123, 38, false,
            report.copy(rows = (0..202).map { row.copy(guideId = "id-$it", fingerprint = "$it") }))
    }
    @Test fun `pages are immutable bounded consistent and automatically released after ttl`() = runTest {
        val data = fixture()
        val session = GuideFeedProbeSession(this, { testScheduler.currentTime }, { 123 })
        var entered = false
        val probe: suspend (Long, Long) -> GuideFeedProbeEvidence = { feed, now ->
            entered = true; assertEquals(1L, feed); assertEquals(123L, now); data }
        assertEquals("INVALID_REQUEST", session.handle("start", null, -1, probe = probe).status)
        val first = session.handle("start", null, 1, probe = probe)
        assertFalse(entered); assertEquals("RUNNING", first.status)
        assertEquals("BUSY", session.handle("start", null, 1, probe = probe).status)
        runCurrent()
        val page = session.handle("poll", first.token, offsetRequested = -10, limit = 999, probe = probe)
        assertEquals("FINISHED", page.status); assertEquals(203, page.total); assertEquals(0, page.offset)
        assertEquals(100, page.evidence!!.report.rows.size)
        val tail = session.handle("poll", first.token, offsetRequested = 200, probe = probe)
        assertEquals(3, tail.evidence!!.report.rows.size); assertEquals("id-200", tail.evidence.report.rows.first().guideId)
        assertEquals(page.evidence.report.streamFingerprint, tail.evidence.report.streamFingerprint)
        assertEquals("UNKNOWN_TOKEN", session.handle("poll", "bad", probe = probe).status)
        assertEquals("FINISHED", session.handle("cancel", first.token, probe = probe).status)
        advanceTimeBy(300_001); runCurrent()
        val expired = session.handle("poll", first.token, probe = probe)
        assertEquals("EXPIRED", expired.status); assertNull(expired.evidence)
    }
    @Test fun `timeout cancellation process loss and expiry generation cannot claim completeness`() = runTest {
        val data = fixture()
        val session = GuideFeedProbeSession(this, { testScheduler.currentTime })
        val waiting: suspend (Long, Long) -> GuideFeedProbeEvidence = { _, _ -> awaitCancellation() }
        val first = session.handle("start", null, 1, probe = waiting)
        runCurrent(); advanceTimeBy(55_001); runCurrent()
        assertEquals("CANCELLED_OR_TIMED_OUT", session.handle("poll", first.token, probe = waiting).status)
        val second = session.handle("start", null, 1, probe = waiting)
        runCurrent()
        assertEquals("CANCELLED", session.handle("cancel", second.token, probe = waiting).status)
        val third = session.handle("start", null, 1) { _, _ -> data }
        runCurrent()
        assertEquals("FINISHED", session.handle("poll", third.token, probe = waiting).status)
        assertEquals("UNKNOWN_TOKEN", GuideFeedProbeSession(this).handle("poll", third.token, probe = waiting).status)
        advanceTimeBy(299_999); runCurrent()
        assertEquals("FINISHED", session.handle("poll", third.token, probe = waiting).status)
        advanceTimeBy(2); runCurrent()
        assertEquals("EXPIRED", session.handle("poll", third.token, probe = waiting).status)
    }
}
