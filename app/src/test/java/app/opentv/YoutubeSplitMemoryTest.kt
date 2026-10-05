/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv

import app.opentv.ui.player.SplitScreenMemory
import app.opentv.youtube.YoutubeVideo
import app.opentv.data.model.Channel
import org.junit.Assert.*
import org.junit.Test

class YoutubeSplitMemoryTest {
    private val video = YoutubeVideo("https://www.youtube.com/watch?v=test", "Test", "Uploader")
    @Test fun `right YouTube restores prior selection audio expansion and fill without retuning unchanged left`() {
        val memory = SplitScreenMemory()
        memory.selectedPane = 0; memory.audioPane = 0; memory.expandedPane = 0; memory.fillPictures = true
        memory.beginYoutube(1, video, 42)
        assertTrue(memory.hasPicture(1)); assertTrue(memory.hasYoutube)
        assertEquals(1, memory.audioPane)
        memory.expandedPane = 1; memory.fillPictures = false
        assertNull(memory.restoreTv(42))
        assertEquals(0, memory.selectedPane); assertEquals(0, memory.audioPane)
        assertEquals(0, memory.expandedPane); assertTrue(memory.fillPictures)
        assertFalse(memory.hasYoutube); assertNull(memory.youtubeVideo); assertFalse(memory.hasPicture(1))
    }
    @Test fun `left YouTube forces restoration of original TV even when channel metadata is unchanged`() {
        val memory = SplitScreenMemory()
        memory.beginYoutube(0, video, 42)
        assertEquals(42L, memory.restoreTv(42))
        assertNull(memory.restoreTv(42))
    }
    @Test fun `changing YouTube result or moving panes never overwrites original TV snapshot`() {
        val memory = SplitScreenMemory()
        memory.beginYoutube(0, video, 42)
        assertEquals(42L, memory.beginYoutube(1, video.copy(title = "Another"), 99))
        assertEquals(42L, memory.restoreTv(99))
    }
    @Test fun `changing back to TV leaves Netflix restoration available without stale YouTube`() {
        val memory = SplitScreenMemory()
        memory.beginYoutube(0, video, 42)
        memory.clearYoutubePicture(0)
        assertTrue(memory.hasYoutube); assertNull(memory.youtubeVideo)
        assertEquals(42L, memory.restoreTv(99))
    }
    @Test fun `restore brings back original second channel after it was replaced during YouTube`() {
        val original = Channel(id = 7, sourceId = 1, streamId = "test", name = "TV",
            categoryId = null, logoUrl = null, epgChannelId = null, number = null, streamUrl = "https://example.invalid/test")
        val memory = SplitScreenMemory()
        memory.secondaryChannel = original
        memory.audioPane = 1; memory.selectedPane = 1
        memory.beginYoutube(1, video, 42)
        memory.clearYoutubePicture(1)
        memory.secondaryChannel = original.copy(id = 8)
        assertNull(memory.restoreTv(42))
        assertEquals(original, memory.secondaryChannel)
        assertEquals(1, memory.audioPane); assertEquals(1, memory.selectedPane)
    }
}
