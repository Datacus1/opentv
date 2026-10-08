package app.opentv

import app.opentv.data.model.Programme
import app.opentv.data.repo.GuideProgrammeSelector
import org.junit.Assert.*
import org.junit.Test

class GuideProgrammeSelectorTest {
    private val now = 1_800_000_000_000L
    private fun p(id: String, start: Long, end: Long = start + 1000) =
        Programme(feedId = 1, epgChannelId = id, startUtcMillis = start, endUtcMillis = end, title = "Synthetic")
    private fun selector(limit: Int = 20, channels: Int = 3) = GuideProgrammeSelector(
        now, now - 10000, now + 20000, limit, channels)

    @Test fun `late channels keep current and nearest next despite early channel flood`() {
        val s = selector(12)
        (1..1000).forEach { s.offer(p("early", now + it)) }
        for (id in listOf("early", "late", "last")) {
            s.offer(p(id, now - 10, now + 10))
            s.offer(p(id, now + 2))
        }
        val keys = s.selected()
        assertTrue(keys.size <= 12)
        for (id in listOf("early", "late", "last")) {
            assertTrue(GuideProgrammeSelector.Key(id, now - 10) in keys)
            assertTrue(GuideProgrammeSelector.Key(id, now + 2) in keys)
        }
    }
    @Test fun `within channel cap shuffled feed keeps same identities`() {
        val input = (1..100).flatMap { t -> listOf(p("a", now + t), p("b", now + t), p("c", now + t)) } +
            listOf(p("a", now - 100, now + 100), p("b", now - 50, now + 50))
        val a = selector(); val b = selector()
        input.forEach(a::offer); input.reversed().forEach(b::offer)
        assertEquals(a.selected(), b.selected())
    }
    @Test fun `retention uses overlap and excludes exact boundaries`() {
        val s = selector()
        s.offer(p("span", now - 50000, now + 1000))
        s.offer(p("old", now - 11000, now - 10000))
        s.offer(p("future", now + 20000))
        assertEquals(setOf(GuideProgrammeSelector.Key("span", now - 50000)), s.selected())
    }
    @Test fun `duplicate programme keys cannot crowd out other current and next rows`() {
        val s = selector()
        repeat(20000) { s.offer(p("a", now + 100)) }
        s.offer(p("b", now - 100, now + 100)); s.offer(p("b", now + 150))
        assertTrue(GuideProgrammeSelector.Key("b", now - 100) in s.selected())
        assertTrue(GuideProgrammeSelector.Key("b", now + 150) in s.selected())
        assertFalse(s.truncated(s.selected()))
    }
    @Test fun `channel cap is explicit and identity budget cannot grow beyond limit`() {
        val s = selector()
        repeat(1000) { s.offer(p("channel-$it", now + 100)) }
        assertTrue(s.selected().size <= 20)
        assertTrue(s.truncated(s.selected()))
    }
    @Test fun `one past and two future days remain the production horizon`() {
        assertEquals(24 * 60 * 60 * 1000L, app.opentv.data.repo.EpgRepository.RETENTION_PAST_MILLIS)
        assertEquals(48 * 60 * 60 * 1000L, app.opentv.data.repo.EpgRepository.RETENTION_FUTURE_MILLIS)
    }
    @Test fun `explicit country identity cannot become a different country's raw id`() {
        val index = app.opentv.data.repo.EpgMatcher.buildIndex(listOf("ABC.uk" to "UK-only station"))
        assertNull(index.matchProvider("unknownstation", "ABC.us"))
        val revision = app.opentv.data.repo.EpgMatcher.buildIndex(listOf("ABC.us2" to "US station"))
        assertEquals("ABC.us2", revision.matchProvider("unknownstation", "ABC.us"))
    }
    @Test fun `single row budget prefers current and still permits an update`() {
        val s = selector(1)
        s.offer(p("a", now + 100)); s.offer(p("a", now - 100, now + 100))
        assertEquals(setOf(GuideProgrammeSelector.Key("a", now - 100)), s.selected())
        assertTrue(s.truncated(s.selected()))
    }
}
