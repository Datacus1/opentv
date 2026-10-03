package app.opentv

import app.opentv.data.model.Channel
import app.opentv.ui.player.SplitScreenAudioRouter
import app.opentv.ui.player.SplitScreenPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitScreenPolicyTest {
    @Test
    fun `every focus expansion and lifecycle combination has at most one audible pane`() {
        for (audio in 0..1) for (expanded in listOf(null, 0, 1)) {
            for (ready in listOf(false, true)) for (foreground in listOf(false, true)) {
                val (left, right) = SplitScreenPolicy.volumes(audio, expanded, ready, foreground)
                assertTrue(left == 0f || right == 0f)
                if (!foreground) assertEquals(0f to 0f, left to right)
                if (foreground && ready && expanded != null) {
                    assertEquals(if (expanded == 0) 1f to 0f else 0f to 1f, left to right)
                }
                if (!ready) assertEquals(0f, right)
            }
        }
    }

    @Test
    fun `audio handoff never mixes and detaching does not touch released secondary`() {
        var left = 1f
        var right = 0f
        var released = false
        val router = SplitScreenAudioRouter {
            left = it
            assertTrue(left == 0f || right == 0f)
        }
        router.attachSecondary {
            assertTrue(!released)
            right = it
            assertTrue(left == 0f || right == 0f)
        }
        repeat(5) {
            router.route(0f to 1f)
            assertEquals(0f to 1f, left to right)
            router.route(1f to 0f)
            assertEquals(1f to 0f, left to right)
        }
        router.route(0f to 0f)
        router.detachSecondary()
        released = true
        router.route(0f to 1f)
        assertEquals(0f to 0f, left to right)
        router.route(1f to 0f)
        assertEquals(1f to 0f, left to right)
    }

    @Test
    fun `same channel quality variants cannot open a duplicate connection`() {
        val original = channel(1, "FOX East HD", "foxeast", 200)
        val choices = SplitScreenPolicy.choices(
            listOf(original, channel(2, "FOX East FHD", "foxeast", 300), channel(3, "ESPN HD", "espn", 200)),
            original,
        )
        assertEquals(listOf(3L), choices.map { it.id })
    }

    @Test
    fun `choosing another source or category keeps correct database identity and favourite`() {
        val choices = SplitScreenPolicy.choices(
            listOf(
                channel(1, "ESPN 4K", "espn", 400),
                channel(2, "ESPN FHD", "espn", 300),
                channel(3, "ESPN HD", "espn", 200).copy(favourite = true),
                channel(4, "FOX HD", "fox", 200).copy(sourceId = 2),
                channel(5, "Hidden", "hidden", 100).copy(hidden = true),
            ),
            channel(6, "FOX HD", "fox", 200),
        )
        assertEquals(setOf(2L, 4L), choices.map { it.id }.toSet())
        assertTrue(choices.first { it.id == 2L }.favourite)
    }

    @Test
    fun `blank stored group keys use channel names instead of merging unrelated channels`() {
        val choices = SplitScreenPolicy.choices(
            listOf(channel(1, "ABC HD", "", 200), channel(2, "NBC HD", "", 200)),
            channel(3, "FOX HD", "", 200),
        )
        assertEquals(2, choices.size)
    }

    private fun channel(id: Long, name: String, groupKey: String, quality: Int) = Channel(
        id = id,
        sourceId = 1,
        streamId = "test-$id",
        name = name,
        groupKey = groupKey,
        qualityRank = quality,
        categoryId = "sports",
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "https://example.invalid/channel-$id",
    )
}
