/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

/** Mute both outputs before every handoff so switching can never mix two audio tracks. */
internal class SplitScreenAudioRouter(private val setPrimaryVolume: (Float) -> Unit) {
    private var setSecondaryVolume: ((Float) -> Unit)? = null
    private var requested = 1f to 0f
    private var closed = false

    fun attachSecondary(setVolume: (Float) -> Unit) {
        if (closed) return
        setSecondaryVolume?.invoke(0f)
        setSecondaryVolume = setVolume
        applyVolumes()
    }

    /** Call before releasing the secondary decoder; no later route can touch a released player. */
    fun detachSecondary() {
        setSecondaryVolume?.invoke(0f)
        setSecondaryVolume = null
    }

    fun route(volumes: Pair<Float, Float>) {
        if (closed) return
        requested = volumes
        applyVolumes()
    }

    /** Terminal shutdown before promotion/disposal; delayed effects cannot unmute old outputs. */
    fun close() {
        if (closed) return
        setPrimaryVolume(0f)
        detachSecondary()
        closed = true
    }

    private fun applyVolumes() {
        setPrimaryVolume(0f)
        setSecondaryVolume?.invoke(0f)
        if (requested.first > 0f) setPrimaryVolume(requested.first)
        else if (requested.second > 0f) setSecondaryVolume?.invoke(requested.second)
    }
}
