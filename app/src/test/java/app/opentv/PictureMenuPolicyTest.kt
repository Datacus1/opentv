package app.opentv

import app.opentv.ui.player.MenuSelectActivation
import app.opentv.ui.player.SplitControlsWakeGuard
import app.opentv.ui.player.SplitScreenPolicy
import org.junit.Assert.*
import org.junit.Test

class PictureMenuPolicyTest {
    @Test fun `dialog restoration cannot steal selection before requested pane accepts focus`() {
        assertFalse(SplitScreenPolicy.acceptPaneFocus(1, 0))
        assertTrue(SplitScreenPolicy.acceptPaneFocus(1, 1))
        assertFalse(SplitScreenPolicy.acceptPaneFocus(0, 1))
        assertTrue(SplitScreenPolicy.acceptPaneFocus(null, 0))
        assertTrue(SplitScreenPolicy.acceptPaneFocus(null, 1))
    }
    @Test fun `opening select release and repeats cannot choose the first menu row`() {
        val activation = MenuSelectActivation()
        assertFalse(activation.activate(100, true, 0))
        assertFalse(activation.activate(100, false, 1))
        assertFalse(activation.activate(100, true, 0))
        assertFalse(activation.activate(200, false, 0))
        assertFalse(activation.activate(200, false, 1))
        assertTrue(activation.activate(200, true, 0))
        assertFalse(activation.activate(200, true, 0))
    }
    @Test fun `whole hidden-screen wake press drains before menu activation`() {
        val guard = SplitControlsWakeGuard()
        guard.start(100)
        repeat(10) { assertTrue(guard.consumeTail(100, false)) }
        assertTrue(guard.consumeTail(100, true))
        assertFalse(guard.consumeTail(200, false))
    }
    @Test fun `sound follows changed selection but preserves explicit choice on focus restoration`() {
        assertEquals(1, SplitScreenPolicy.audioAfterSelection(0, 1, 0))
        assertEquals(0, SplitScreenPolicy.audioAfterSelection(1, 0, 1))
        assertEquals(0, SplitScreenPolicy.audioAfterSelection(1, 1, 0))
        assertEquals(1, SplitScreenPolicy.audioAfterSelection(0, 0, 1))
        for (selected in 0..1) {
            val volumes = SplitScreenPolicy.volumes(selected, null, true, true)
            assertEquals(1f, volumes.first + volumes.second)
        }
    }
}
