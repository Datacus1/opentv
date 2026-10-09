package app.opentv

import app.opentv.data.model.*
import app.opentv.data.repo.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GuideIdentityResolverTest {
    private fun channel(id: Long, name: String, epg: String? = null) = Channel(id = id, sourceId = 1,
        streamId = "not-exported", name = name, categoryId = null, logoUrl = null,
        epgChannelId = epg, number = null, streamUrl = "not-exported")
    private val feeds = listOf(EpgFeed(1, "Provider", providerSourceId = 1), EpgFeed(2, "Public"))
    private fun alias(id: String, name: String, feed: Long = 1) = EpgChannelAlias(feed, id, name, "unused")
    private fun resolve(channels: List<Channel>, aliases: List<EpgChannelAlias>) =
        GuideIdentityResolver(channels, feeds, aliases, aliases.map { it.epgId }.toSet())

    @Test fun `shared corrupted id is quarantined even for its last alias victim and never bypasses candidates`() {
        val channels = listOf(channel(1, "US: DUST", "COVID19"),
            channel(2, "GA | Atlanta | CW WPCH", "COVID19"))
        val resolver = resolve(channels, listOf(alias("COVID19", channels[1].name)))
        channels.forEach {
            assertNull(resolver.resolve(it).id)
            assertTrue(resolver.resolve(it).rejectedProviderId)
            assertTrue(it.epgCandidates.isEmpty())
        }
    }
    @Test fun `same-name quality variants retain valid provider id and public collision does not hide literal id`() {
        val channels = listOf(channel(1, "US: CURIOSITY NOW HD", "CuriosityNOW.us"),
            channel(2, "US-SD | Curiosity Now", "CuriosityNOW.us"))
        val resolver = resolve(channels, listOf(alias("CuriosityNOW.us", "US: CURIOSITY NOW"),
            alias("Curiosity.NOW.us2", "Curiosity NOW", 2)))
        channels.forEach { assertEquals("CuriosityNOW.us", resolver.resolve(it).id) }
    }
    @Test fun `country and California local station are distinct from a generic country prefix`() {
        val channels = listOf(channel(1, "CA: CNN"), channel(2, "US: CNN"),
            channel(3, "CA | Los Angeles | ABC KABC", "ABCKABC.us"))
        val resolver = resolve(channels, listOf(alias("CNN.us", "US: CNN"),
            alias("ABCKABC.us", "CA | Los Angeles | ABC KABC")))
        assertNull(resolver.resolve(channels[0]).id)
        assertEquals("CNN.us", resolver.resolve(channels[1]).id)
        assertEquals("ABCKABC.us", resolver.resolve(channels[2]).id)
    }
    @Test fun `source exact names resolve cross-feed aliases without guessing numbering direction or plus`() {
        val channels = listOf(channel(1, "US-SD | Great American Family"),
            channel(2, "US-SD | SportsNet Pittsburgh"), channel(3, "UK: DAZN 11"),
            channel(4, "US: HGTV WEST"), channel(5, "US: AMC PLUS"))
        val resolver = resolve(channels, listOf(alias("GAF.us", "US: Great American Family"),
            alias("GAF.us2", "Great American Family HD", 2),
            alias("Pittsburgh.us2", "SportsNet Pittsburgh HD", 2),
            alias("PittsburghPlus.us2", "SportsNet Pittsburgh Plus", 2),
            alias("DAZN1.de", "DE: DAZN 1"), alias("HGTV.us", "US: HGTV"),
            alias("AMC.us", "US: AMC")))
        assertEquals("GAF.us", resolver.resolve(channels[0]).id)
        assertEquals("Pittsburgh.us2", resolver.resolve(channels[1]).id)
        channels.drop(2).forEach { assertNull(resolver.resolve(it).id) }
    }
    @Test fun `manual override remains explicit and accepted provider IDs become validated matched candidates`() {
        val channel = channel(1, "Unknown", "wrong").copy(epgOverrideId = "manual", matchedEpgId = "validated")
        assertEquals(listOf("manual", "validated"), channel.epgCandidates)
    }

    @Test fun `foreign ID hint and four K event identity do not become generic US names`() {
        val channels = listOf(channel(1, "CAR: DISNEY JUNIOR", "DisneyJunior.bs"),
            channel(2, "PC: NBC Sports", "COVID19"), channel(3, "US: DUST", "COVID19"))
        val resolver = resolve(channels, listOf(alias("DisneyJunior.us", "US: DISNEY JUNIOR"),
            alias("NBC.Sports.4K.us2", "NBC Sports 4K", 2)))
        channels.forEach { assertNull(resolver.resolve(it).id) }
    }
    @Test fun `other provider aliases cannot be used by source fallback or create a shared ID conflict`() {
        val c1 = channel(1, "US: News", "id")
        val c2 = channel(2, "US: Other", "id").copy(sourceId = 2)
        val c3 = channel(3, "US: Private Other")
        val aliases = listOf(alias("id", "US: News"), alias("id", "US: Other", 3),
            alias("other", "US: Private Other", 3))
        val resolver = GuideIdentityResolver(listOf(c1, c2, c3), feeds +
            EpgFeed(3, "Other provider", providerSourceId = 2), aliases, setOf("id", "other"))
        assertEquals("id", resolver.resolve(c1).id)
        assertFalse(resolver.resolve(c1).rejectedProviderId)
        assertNull(resolver.resolve(c3).id)
    }
    @Test fun `case-sensitive literal ids never select the first casefold collision`() {
        val idx = EpgMatcher.buildIndex(listOf("News.us" to "First", "news.us" to "Second"))
        assertEquals("News.us", idx.matchId("News.us"))
        assertEquals("news.us", idx.matchId("news.us"))
        assertNull(idx.matchId("NEWS.us"))
    }
    @Test fun `quarantined ID cannot supply a US country hint for regional channels`() {
        val channels = listOf(channel(1, "CAR: CARTOON NETWORK", "LogoTV.us"),
            channel(2, "CAR: CANAL 11", "LogoTV.us"), channel(3, "CAR: CGTN"), channel(4, "LA: CGTN"))
        val resolver = resolve(channels, listOf(alias("TOONLSE", "US-SD | Cartoon Network"),
            alias("CGTN.ca", "CA: CGTN"), alias("LogoTV.us", "CAR: CANAL 11")))
        channels.forEach { assertNull(resolver.resolve(it).id) }
    }
    @Test fun `a genuinely conflicted global guide ID stays quarantined across feeds until feed-qualified UI exists`() {
        val channels = listOf(channel(1, "US: DUST", "bad"), channel(2, "US: News", "bad"),
            channel(3, "US: Other", "bad").copy(sourceId = 2))
        val aliases = listOf(alias("bad", "US: News"), alias("bad", "US: Other", 3))
        val resolver = GuideIdentityResolver(channels, feeds + EpgFeed(3, "Other", providerSourceId = 2),
            aliases, setOf("bad"))
        channels.forEach { assertNull(resolver.resolve(it).id) }
    }

    /** Opt-in local safe metadata replay; no private URLs/models, network or device mutations. */
    @Test fun `replay every actual identity with an exhaustive decision report`() {
        val path = System.getenv("OPENTV_GUIDE_IDENTITY_FIXTURE")
        assumeTrue("Provide allowlisted local identity snapshot", !path.isNullOrBlank())
        val root = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
        val channels = root.getValue("channels").jsonArray.map { entry ->
            val row = entry.jsonObject
            channel(row.getValue("channelId").jsonPrimitive.long, row.text("name")!!, row.text("providerId"))
                .copy(sourceId = row.getValue("sourceId").jsonPrimitive.long,
                    matchedEpgId = row.text("matchedId"), epgOverrideId = row.text("overrideId"))
        }
        val rows = root.getValue("aliases").jsonArray.map { it.jsonObject }
        val feeds = rows.groupBy { it.getValue("feedId").jsonPrimitive.long }.map { (id, rows) ->
            EpgFeed(id, "Anonymous", enabled = rows.first().getValue("enabled").jsonPrimitive.boolean,
                providerSourceId = rows.first().text("providerSourceId")?.toLong())
        }
        val aliases = rows.map { row -> alias(row.text("guideId")!!, row.text("name")!!,
            row.getValue("feedId").jsonPrimitive.long) }
        val populated = rows.filter { it.getValue("totalRows").jsonPrimitive.int > 0 }
            .map { it.text("guideId")!! }.toSet()
        val resolver = GuideIdentityResolver(channels, feeds, aliases, populated)
        val report = channels.map { c ->
            val d = resolver.resolve(c)
            assertTrue(d.id == null || d.id in populated)
            if (c.name.contains("US Asian") || c.name.contains("US Arabic")) assertNotEquals("USA", d.id)
            if (c.name.matches(Regex("(?i).*DAZN\\s*(1[1-9]|2[0-9]).*")))
                assertTrue(d.id !in setOf("DAZN1.de", "DAZN2.de"))
            assertNotEquals("COVID19", d.id)
            if (c.name == "CAR: DISNEY JUNIOR") assertNotEquals("DisneyJunior.us", d.id)
            if (c.name == "PC: NBC Sports") assertNotEquals("NBC.Sports.4K.us2", d.id)
            buildJsonObject {
                put("channelId", c.id); put("name", c.name); put("providerId", c.epgChannelId)
                put("oldMatchedId", c.matchedEpgId); put("newMatchedId", d.id)
                put("reason", d.reason); put("rejectedProviderId", d.rejectedProviderId)
            }
        }
        val output = System.getenv("OPENTV_GUIDE_IDENTITY_REPORT")
        val baseline = System.getenv("OPENTV_GUIDE_IDENTITY_BASELINE")
        if (!baseline.isNullOrBlank()) {
            val previous = Json.parseToJsonElement(File(baseline).readText()).jsonArray
                .associate { it.jsonObject.getValue("channelId") to it.jsonObject.getValue("newMatchedId") }
            assertEquals(channels.size, previous.size)
            report.forEach { row -> assertEquals("Optimized identity differs for ${row["channelId"]}",
                previous[row.getValue("channelId")], row.getValue("newMatchedId")) }
        }
        if (!output.isNullOrBlank()) File(output).writeText(Json { prettyPrint = true }.encodeToString(
            JsonElement.serializer(), JsonArray(report)))
        println("Actual identities=${channels.size}; accepted=${report.count { it["newMatchedId"] !is JsonNull }}")
    }
}
