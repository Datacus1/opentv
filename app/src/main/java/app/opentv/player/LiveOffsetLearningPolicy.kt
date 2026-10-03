/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

/** A tune exposed to multiview remains tainted until a fresh single-channel tune. */
internal class LiveOffsetLearningPolicy(private var multiview: Boolean = false) {
    private var isolatedTune = !multiview
    val mayPersist: Boolean get() = isolatedTune && !multiview

    fun setMultiview(active: Boolean) {
        multiview = active
        if (active) isolatedTune = false
    }

    fun beginTune() { isolatedTune = !multiview }
}
