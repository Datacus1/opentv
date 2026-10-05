/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The protected ADB receiver signals the current player route, never a saved credential. */
object YoutubeSplitSignals {
    @Volatile var active = false
    private val sequence = MutableStateFlow(0L)
    val restoreRequests = sequence.asStateFlow()
    fun restoreTv() { sequence.value += 1 }
}
