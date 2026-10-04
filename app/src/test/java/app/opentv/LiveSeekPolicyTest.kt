package app.opentv

import app.opentv.player.LiveSeekPolicy
import app.opentv.player.LiveWindowRecoveryGate
import org.junit.Assert.*
import org.junit.Test

class LiveSeekPolicyTest {
    @Test fun `seeks remain inside the moving window and behind safe live position`() {
        val bounds = LiveSeekPolicy.bounds(120_000, 100_000, 30_000)!!
        assertEquals(2_000L..90_000L, bounds)
        assertEquals(2_000L, LiveSeekPolicy.target(3_000, -15_000, bounds))
        assertEquals(90_000L, LiveSeekPolicy.target(89_000, 15_000, bounds))
        assertEquals(60_000L, LiveSeekPolicy.target(45_000, 15_000, bounds))
    }
    @Test fun `short and unknown windows do not advertise unavailable replay`() {
        assertNull(LiveSeekPolicy.bounds(-1, -1, 30_000))
        assertNull(LiveSeekPolicy.bounds(30_000, 0, 30_000))
        assertNull(LiveSeekPolicy.bounds(32_000, 2_000, 30_000))
        assertEquals(2_000L..60_000L, LiveSeekPolicy.bounds(120_000, 60_000, 30_000))
    }
    @Test fun `missing default position falls back to safety and learned offset is retained`() {
        assertEquals(2_000L..75_000L, LiveSeekPolicy.bounds(120_000, -1, 45_000))
        assertEquals(2_000L..60_000L, LiveSeekPolicy.bounds(120_000, 90_000, 60_000))
    }
    @Test fun `progress stays bounded and time labels describe elapsed time`() {
        assertEquals(0f, LiveSeekPolicy.progress(-1, 2_000L..90_000L))
        assertEquals(1f, LiveSeekPolicy.progress(200_000, 2_000L..90_000L))
        assertEquals("00:45", LiveSeekPolicy.clock(45_999))
        assertEquals("1:02:03", LiveSeekPolicy.clock(3_723_000))
    }
    @Test fun `invalid provider cannot trigger an endless automatic recovery loop`() {
        val gate = LiveWindowRecoveryGate()
        assertTrue(gate.allow(1000)); assertTrue(gate.allow(2000)); assertFalse(gate.allow(3000))
        assertTrue(gate.allow(31_000))
        gate.reset(); assertTrue(gate.allow(31_001))
    }
    @Test fun `feeds without programme timestamps use an explicitly approximate live offset`() {
        assertEquals(45_000L, LiveSeekPolicy.liveOffset(-1, 120_000, 75_000))
        assertEquals(42_000L, LiveSeekPolicy.liveOffset(42_000, 120_000, 75_000))
        assertEquals(0L, LiveSeekPolicy.liveOffset(-1, 120_000, 125_000))
    }
}
