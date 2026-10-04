/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import android.view.KeyEvent

/** Directional navigation must never double as a live-channel shortcut. */
internal object PlayerRemotePolicy {
    enum class VerticalAction { DELEGATE, REVEAL_CONTROLS, PREVIOUS_CHANNEL, NEXT_CHANNEL }

    fun verticalAction(keyCode: Int, pickerOwnsDpad: Boolean): VerticalAction {
        // Lists and track/quality/aspect pickers retain their normal vertical navigation.
        if (pickerOwnsDpad) return VerticalAction.DELEGATE
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> VerticalAction.REVEAL_CONTROLS
            KeyEvent.KEYCODE_CHANNEL_UP -> VerticalAction.PREVIOUS_CHANNEL
            KeyEvent.KEYCODE_CHANNEL_DOWN -> VerticalAction.NEXT_CHANNEL
            else -> VerticalAction.DELEGATE
        }
    }
}
