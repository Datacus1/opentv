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

    @Test fun `prepared scoped keys preserve literal canonical and name collision semantics`() {
        val aliases = listOf("CuriosityNOW.us" to "Curiosity NOW", "Curiosity.NOW.us2" to "Curiosity NOW",
            "News.us" to "First", "news.us" to "Second", "NESNplus.us2" to "NESN PLUS",
            "ESPN.us" to "ESPN", "other" to "Duplicate", "third" to "Duplicate")
        val ordinary = EpgMatcher.buildIndex(aliases)
        val prepared = EpgMatcher.buildPreparedIndex(aliases.map { (id, name) ->
            EpgMatcher.prepareAlias(id, ChannelNameNormalizer.normalize(name).groupKey)
        })
        for (id in aliases.map { it.first } + listOf("NEWS.us", "NESNplus.us", "ESPN.us2", "none"))
            assertEquals(id, ordinary.matchId(id), prepared.matchId(id))
        for (key in aliases.map { ChannelNameNormalizer.groupKeyOf(it.second) } + listOf("espn2", "nesn")) {
            assertEquals(key, ordinary.matchProvider(key, null), prepared.matchProvider(key, null))
            assertEquals(key, ordinary.hasAmbiguousPrefix(key), prepared.hasAmbiguousPrefix(key))
        }
    }
}
