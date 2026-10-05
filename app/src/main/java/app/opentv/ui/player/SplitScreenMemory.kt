/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.opentv.data.model.Channel
import app.opentv.youtube.YoutubeVideo

/** Ephemeral UI memory, owned by PlayerScreen; no signed links or new disk persistence. */
internal class SplitScreenMemory {
    var secondaryChannel by mutableStateOf<Channel?>(null)
    var selectedPane by mutableStateOf(0)
    var audioPane by mutableStateOf(0)
    var expandedPane by mutableStateOf<Int?>(null)
    var fillPictures by mutableStateOf(false)
    var youtubePane by mutableStateOf<Int?>(null)
    var youtubeVideo by mutableStateOf<YoutubeVideo?>(null)
    private var tvSnapshot: TvSnapshot? = null

    fun beginYoutube(pane: Int, video: YoutubeVideo, primaryId: Long): Long? {
        require(pane in 0..1)
        if (tvSnapshot == null) tvSnapshot = TvSnapshot(primaryId, secondaryChannel,
            selectedPane, audioPane, expandedPane, fillPictures)
        val restorePrimary = if (youtubePane == 0 && pane == 1) tvSnapshot?.primaryId else null
        youtubePane = pane
        youtubeVideo = video
        selectedPane = pane
        audioPane = pane
        return restorePrimary
    }

    /** Restore both previous TV choices and layout, returning the channel to retune if necessary. */
    fun restoreTv(currentPrimaryId: Long?): Long? {
        val saved = tvSnapshot ?: return null
        val retune = saved.primaryId.takeIf { youtubePane == 0 || it != currentPrimaryId }
        secondaryChannel = saved.secondary
        selectedPane = saved.selected
        audioPane = saved.audio
        expandedPane = saved.expanded
        fillPictures = saved.fill
        youtubePane = null
        youtubeVideo = null
        tvSnapshot = null
        return retune
    }

    fun clearYoutubePicture(pane: Int) {
        if (youtubePane == pane) { youtubePane = null; youtubeVideo = null }
        // Keep the original TV snapshot until Netflix restoration or exit.
    }
    val hasYoutube: Boolean get() = tvSnapshot != null
    fun hasPicture(pane: Int): Boolean = pane == 0 || secondaryChannel != null ||
        (youtubePane == pane && youtubeVideo != null)
    private class TvSnapshot(val primaryId: Long, val secondary: Channel?, val selected: Int,
        val audio: Int, val expanded: Int?, val fill: Boolean)
}
