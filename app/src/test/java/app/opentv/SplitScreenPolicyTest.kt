package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.ui.player.SplitControlsWakeGuard
import app.opentv.ui.player.SplitScreenAudioRouter
import app.opentv.ui.player.SplitScreenPolicy
import app.opentv.player.LiveOffsetLearningPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitScreenPolicyTest {
    @Test
    fun `fill halves can crop but expanded pictures always preserve the whole frame`() {
        assertEquals(0, SplitScreenPolicy.resizeMode(false, false))
        assertEquals(4, SplitScreenPolicy.resizeMode(true, false))
        assertEquals(0, SplitScreenPolicy.resizeMode(false, true))
        assertEquals(0, SplitScreenPolicy.resizeMode(true, true))
    }

    @Test
    fun `waking controls consumes repeats and release but allows the next select`() {
        val guard = SplitControlsWakeGuard()
        assertTrue(!guard.consumeTail(10L, false))
        guard.start(10L)
        repeat(8) { assertTrue(guard.consumeTail(10L, false)) }
        assertTrue(guard.consumeTail(10L, true))
        assertTrue(!guard.consumeTail(20L, false))
        assertTrue(!guard.consumeTail(20L, true))
        guard.start(30L)
        // A lost release must not swallow a fresh press forever.
        assertTrue(!guard.consumeTail(40L, false))
    }

    @Test
    fun `split guide uses populated override provider matched and sibling variant ids`() {
        val high = channel(1, "ABC FHD", "abc", 300).copy(
            epgOverrideId = "empty-override", epgChannelId = "empty-provider", matchedEpgId = "empty-match",
        )
        val low = channel(2, "ABC HD", "abc", 200).copy(epgChannelId = "abc", matchedEpgId = "abc")
        val other = channel(3, "FOX HD", "fox", 200).copy(epgOverrideId = "override", epgChannelId = "provider")
        val current = Programme(1, "abc", 100, 200, "Game")
        val preferred = Programme(1, "override", 100, 200, "Override show")
        val result = SplitScreenPolicy.currentProgrammes(listOf(high, low, other), listOf(
            current, preferred, Programme(1, "provider", 100, 200, "Provider show"),
        ), 150)
        assertEquals(current, result[SplitScreenPolicy.logicalKey(high)])
        assertEquals(preferred, result[SplitScreenPolicy.logicalKey(other)])
    }

    @Test
    fun `split guide removes ended programmes advances at boundary and isolates providers`() {
        val first = channel(1, "FOX HD", "fox", 200).copy(epgChannelId = "fox-one", matchedEpgId = "fox-one")
        val second = first.copy(id = 2, sourceId = 2, epgChannelId = "fox-two", matchedEpgId = "fox-two")
        val hidden = channel(3, "Hidden", "hidden", 100).copy(hidden = true, epgChannelId = "hidden", matchedEpgId = "hidden")
        val ended = Programme(1, "fox-one", 100, 200, "Old")
        val next = Programme(1, "fox-one", 200, 300, "New")
        val other = Programme(1, "fox-two", 100, 300, "Other provider")
        val result = SplitScreenPolicy.currentProgrammes(listOf(first, second, hidden), listOf(
            ended, next, other, Programme(1, "hidden", 100, 300, "Hidden show"),
        ), 200)
        assertEquals(next, result[SplitScreenPolicy.logicalKey(first)])
        assertEquals(other, result[SplitScreenPolicy.logicalKey(second)])
        assertEquals(2, result.size)
        assertTrue(SplitScreenPolicy.currentProgrammes(listOf(first), listOf(ended), 200).isEmpty())
    }

    @Test
    fun `recording guard fails closed while loading or recording`() {
        assertTrue(!SplitScreenPolicy.mayOpenSecondary(null))
        assertTrue(!SplitScreenPolicy.mayOpenSecondary(1))
        assertTrue(!SplitScreenPolicy.mayOpenSecondary(3))
        assertTrue(SplitScreenPolicy.mayOpenSecondary(0))
    }

    @Test
    fun `picker restores a surviving channel and falls back safely after filtering`() {
        assertEquals(1, SplitScreenPolicy.restoredChannelIndex(listOf(10L, 20L, 30L), 20L))
        assertEquals(0, SplitScreenPolicy.restoredChannelIndex(listOf(10L, 30L), 20L))
        assertEquals(0, SplitScreenPolicy.restoredChannelIndex(emptyList(), 20L))
        assertEquals(0, SplitScreenPolicy.restoredChannelIndex(listOf(10L), null))
    }

    @Test
    fun `multiview congestion cannot persist into single-channel learning`() {
        val learning = LiveOffsetLearningPolicy()
        assertTrue(learning.mayPersist)
        learning.setMultiview(true)
        assertTrue(!learning.mayPersist)
        learning.beginTune()
        assertTrue(!learning.mayPersist)
        learning.setMultiview(false)
        assertTrue(!learning.mayPersist)
        learning.beginTune()
        assertTrue(learning.mayPersist)
        val extra = LiveOffsetLearningPolicy(true)
        extra.beginTune()
        assertTrue(!extra.mayPersist)
        extra.setMultiview(false)
        assertTrue(!extra.mayPersist)
        extra.beginTune()
        assertTrue(extra.mayPersist)
    }

    @Test
    fun `closed audio router cannot touch transferred or retired players`() {
        var left = 1f
        var right = 0f
        var retired = false
        val router = SplitScreenAudioRouter { check(!retired); left = it }
        router.attachSecondary { check(!retired); right = it }
        router.route(0f to 1f)
        router.close()
        assertEquals(0f to 0f, left to right)
        retired = true
        router.route(1f to 0f)
        router.attachSecondary { error("A closed router cannot reattach a player") }
        router.close()
        router.detachSecondary()
    }

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
