package app.opentv

import android.view.KeyEvent
import app.opentv.ui.player.PlayerRemotePolicy
import app.opentv.ui.player.PlayerRemotePolicy.VerticalAction
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRemotePolicyTest {
    @Test
    fun `up and down reveal controls instead of tuning even through repeated input`() {
        repeat(100) {
            for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                assertEquals(VerticalAction.REVEAL_CONTROLS, PlayerRemotePolicy.verticalAction(key, false))
            }
        }
    }

    @Test
    fun `open picker owns vertical keys rather than waking or tuning`() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            assertEquals(VerticalAction.DELEGATE, PlayerRemotePolicy.verticalAction(key, true))
        }
    }

    @Test
    fun `dedicated channel keys keep their distinct shortcut`() {
        assertEquals(VerticalAction.PREVIOUS_CHANNEL,
            PlayerRemotePolicy.verticalAction(KeyEvent.KEYCODE_CHANNEL_UP, false))
        assertEquals(VerticalAction.NEXT_CHANNEL,
            PlayerRemotePolicy.verticalAction(KeyEvent.KEYCODE_CHANNEL_DOWN, false))
    }

    @Test
    fun `other controls are untouched`() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_1)) {
            assertEquals(VerticalAction.DELEGATE, PlayerRemotePolicy.verticalAction(key, false))
        }
    }
}
