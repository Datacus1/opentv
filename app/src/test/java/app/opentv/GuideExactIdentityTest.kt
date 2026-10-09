package app.opentv

import app.opentv.data.repo.EpgMatcher
import app.opentv.data.parser.ChannelNameNormalizer
import org.junit.Assert.*
import org.junit.Test

class GuideExactIdentityTest {
    @Test fun `exact lookup never guesses a numbered timeshift regional or decorated prefix`() {
        val aliases = (0 until 1000).map { "id$it" to "Station Z$it" } + listOf(
            "espn.us" to "ESPN", "dazn1.de" to "DAZN 1", "usa" to "USA",
            "hgtv.us" to "HGTV", "amc.us" to "AMC")
        val index = EpgMatcher.buildIndex(aliases.shuffled(kotlin.random.Random(17)))
        (0 until 1000).forEach {
            assertEquals("id$it", index.match("stationz$it"))
            assertNull(index.match("stationz${it}extra"))
        }
        listOf("ESPN2", "DAZN11", "US Asian Hindi", "HGTV WEST", "AMC+1", "AMC PLUS")
            .forEach { assertNull(it, index.matchProvider(ChannelNameNormalizer.normalize(it).groupKey, null)) }
    }
    @Test fun `literal id wins over display or canonical collision`() {
        val index = EpgMatcher.buildIndex(listOf("CuriosityNOW.us" to "Curiosity NOW",
            "Curiosity.NOW.us2" to "Curiosity NOW"))
        assertEquals("CuriosityNOW.us", index.matchProvider("curiositynow", "CuriosityNOW.us"))
        assertNull(index.match("curiositynow"))
    }
}
