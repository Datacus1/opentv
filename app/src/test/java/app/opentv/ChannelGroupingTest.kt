package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.db.GuideCoverageRow
import app.opentv.data.repo.GuideCoverageAudit
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.ChannelGrouping
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.chooseGuideCandidate
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.player.SplitScreenPolicy
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ChannelGroupingTest {
    private fun channel(id: Long, name: String, guide: String? = null): Channel {
        val normal = ChannelNameNormalizer.normalize(name)
        return Channel(id = id, sourceId = 1, streamId = "synthetic-$id", name = name,
            groupKey = normal.groupKey, qualityRank = normal.qualityRank, qualityLabel = normal.qualityLabel,
            categoryId = null, logoUrl = null, epgChannelId = null, matchedEpgId = guide,
            number = null, streamUrl = "not-exported")
    }
    private fun live(id: String) = Programme(1, id, 100, 200, "Synthetic identity marker")

    @Test fun `US and Australian ESPN remain two rows choices and schedules`() {
        val us = channel(1, "US-SD | ESPN", "ESPN.us")
        val au = channel(2, "AU: ESPN", "ESPN.au")
        assertEquals(us.groupKey, au.groupKey)
        assertEquals(2, ChannelGrouping.groups(listOf(us, au)).size)
        assertEquals(2, SplitScreenPolicy.choices(listOf(us, au), null).size)
        val programmes = listOf(live("ESPN.us"), live("ESPN.au"))
        val result = SplitScreenPolicy.currentProgrammes(listOf(us, au), programmes, 150)
        assertEquals("ESPN.us", result.getValue(SplitScreenPolicy.logicalKey(us)).epgChannelId)
        assertEquals("ESPN.au", result.getValue(SplitScreenPolicy.logicalKey(au)).epgChannelId)
        assertNull(chooseGuideCandidate(listOf(us, au), mapOf("ESPN.au" to listOf(live("ESPN.au"))), 150))
    }

    @Test fun `quarantined Caribbean channel cannot borrow US listing`() {
        val car = channel(1, "CAR: CARTOON NETWORK").copy(epgChannelId = "conflicted")
        val us = channel(2, "US: CARTOON NETWORK", "TOONLSE")
        assertNotEquals(ChannelGrouping.key(car), ChannelGrouping.key(us))
        assertNull(SplitScreenPolicy.currentProgrammes(listOf(car, us), listOf(live("TOONLSE")), 150)
            [SplitScreenPolicy.logicalKey(car)])
        assertEquals(listOf(car), ChannelGrouping.variantsFor(car, listOf(car, us)))
    }

    @Test fun `accepted qualities and category duplicates share only their own identity`() {
        val high = channel(1, "US: FOX SOCCER PLUS FHD", "FoxSoccerPlus.us")
        val low = channel(2, "US: Fox Soccer+ HD", "FoxSoccerPlus.us").copy(categoryId = "other")
        val sameQuality = high.copy(id = 3, categoryId = "third")
        val base = channel(4, "US: Fox Soccer FHD", "FoxSoccer.us")
        assertEquals(ChannelGrouping.key(high), ChannelGrouping.key(low))
        assertEquals(listOf(high, low, sameQuality), ChannelGrouping.variantsFor(high, listOf(high, low, sameQuality, base)))
        assertEquals(2, ChannelGrouping.groups(listOf(high, low, sameQuality, base)).size)
        assertEquals("FoxSoccerPlus.us", chooseGuideCandidate(listOf(high, low),
            mapOf("FoxSoccerPlus.us" to listOf(live("FoxSoccerPlus.us"))), 150))
        val row = ChannelsViewModel.Row(high, listOf(high, low), null, null, emptyList(), listOf(high, low, sameQuality))
        assertEquals(ChannelGrouping.key(high), row.key)
        assertEquals(setOf(1L, 2L, 3L), row.allVariants.map { it.id }.toSet())
    }

    @Test fun `different exact IDs stay separate despite identical country and quality`() {
        val a = channel(1, "US: News HD", "News.us")
        for (id in listOf("News.us2", "NEWS.us", "News-us", "News_us"))
            assertNotEquals(ChannelGrouping.key(a), ChannelGrouping.key(a.copy(id = 2, matchedEpgId = id)))
    }

    @Test fun `unknown or rejected identity never shares approved group`() {
        val known = channel(1, "US: News HD", "News.us")
        val unknown = known.copy(id = 2, matchedEpgId = null, epgChannelId = "News.us")
        assertNotEquals(ChannelGrouping.key(known), ChannelGrouping.key(unknown))
        assertNotEquals(ChannelGrouping.key(unknown.copy(epgChannelId = null)),
            ChannelGrouping.key(unknown.copy(id = 3, epgChannelId = null)))
        assertEquals(ChannelGrouping.key(unknown), ChannelGrouping.key(unknown.copy(id = 3)))
    }

    @Test fun `manual overrides isolate rows and retain empty override fallback`() {
        val a = channel(1, "US: News HD", "News.us")
        val override = a.copy(id = 2, epgOverrideId = "manual")
        assertNotEquals(ChannelGrouping.key(a), ChannelGrouping.key(override))
        assertEquals("manual", chooseGuideCandidate(listOf(override), mapOf(
            "manual" to listOf(Programme(1, "manual", 200, 300, "Upcoming")), "News.us" to listOf(live("News.us"))), 150))
        assertEquals("News.us", chooseGuideCandidate(listOf(override), mapOf("News.us" to listOf(live("News.us"))), 150))
    }

    @Test fun `UI variants isolate sources but recording accepts explicit same identity across sources`() {
        val us = channel(1, "US: ESPN HD", "ESPN.us")
        val other = us.copy(id = 2, sourceId = 2)
        val au = channel(3, "AU: ESPN HD", "ESPN.au").copy(sourceId = 2)
        assertNotEquals(ChannelGrouping.key(us), ChannelGrouping.key(other))
        assertTrue(ChannelGrouping.sameRecordingIdentity(us, other))
        assertFalse(ChannelGrouping.sameRecordingIdentity(us, au))
        assertFalse(ChannelGrouping.sameRecordingIdentity(us.copy(matchedEpgId = null), other.copy(matchedEpgId = null)))
    }

    @Test fun `numbered events directions and separate UHD schedules stay separate`() {
        val names = listOf("US: DAZN 1", "US: DAZN 11", "US: ABC East", "US: ABC West")
        assertEquals(4, ChannelGrouping.groups(names.mapIndexed { i, n -> channel(i.toLong(), n, "same") }).size)
        val standard = channel(10, "US: NBC Sports HD", "NBCSports.us")
        val event = channel(11, "US: NBC Sports 4K", "NBC.Sports.4K.us2")
        assertNotEquals(ChannelGrouping.key(standard), ChannelGrouping.key(event))
        assertNotEquals(ChannelGrouping.key(standard.copy(matchedEpgId = null, epgChannelId = "pending")),
            ChannelGrouping.key(event.copy(matchedEpgId = null, epgChannelId = "pending")))
    }

    @Test fun `short guide IDs are region qualified deterministic bounded and independent of matches`() {
        val us = channel(1, "US: FOX East HD").copy(epgChannelId = "fox")
        val au = channel(2, "AU: FOX East HD").copy(epgChannelId = "fox")
        val key = ChannelGrouping.shortGuideKey(us)
        assertEquals(key, ChannelGrouping.shortGuideKey(us.copy(matchedEpgId = "new", epgOverrideId = "manual")))
        val id = EpgRepository.providerShortEpgId(1, "foxeast", key)
        assertNotEquals(id, EpgRepository.providerShortEpgId(1, "foxeast", ChannelGrouping.shortGuideKey(au)))
        assertEquals(id, EpgRepository.providerShortEpgId(1, "foxeast", key))
        assertTrue(id.length < 128)
    }

    /** Opt-in immutable local metadata replay. No actual programme contents or network/device use. */
    @Test fun `all saved catalogue consumers preserve approved identity and quarantines`() {
        val path = System.getenv("OPENTV_GUIDE_IDENTITY_FIXTURE")
        assumeTrue("Provide safe local snapshot", !path.isNullOrBlank())
        val root = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        val channels = root.getValue("channels").jsonArray.map { it.jsonObject }.map { row ->
            channel(row.getValue("channelId").jsonPrimitive.long, row.text("name")!!, row.text("matchedId"))
                .copy(sourceId = row.getValue("sourceId").jsonPrimitive.long,
                    epgChannelId = row.text("providerId"), epgOverrideId = row.text("overrideId"))
        }
        assertEquals(6581, channels.size)
        val programmes = root.getValue("aliases").jsonArray.map { it.jsonObject }
            .filter { it.getValue("enabled").jsonPrimitive.boolean && it.getValue("currentRows").jsonPrimitive.int > 0 }
            .map { live(it.text("guideId")!!) }
        val byId = programmes.groupBy { it.epgChannelId }
        val groups = ChannelGrouping.groups(channels)
        val current = SplitScreenPolicy.currentProgrammes(channels, programmes, 150)
        var borrowed = 0
        groups.values.forEach { members ->
            assertEquals(1, members.map { it.sourceId }.distinct().size)
            assertTrue(members.mapNotNull { it.matchedEpgId }.distinct().size <= 1)
            val chosen = chooseGuideCandidate(members, byId, 150)
            members.forEach { c ->
                assertTrue(chosen == null || chosen in c.epgCandidates)
                val split = current[SplitScreenPolicy.logicalKey(c)]?.epgChannelId
                assertTrue(split == null || split in c.epgCandidates)
                if (c.epgCandidates.isEmpty() && (chosen != null || split != null)) borrowed++
            }
        }
        assertEquals(0, borrowed)
        val us = channels.single { it.id == 1082L }
        val au = channels.single { it.id == 5349L }
        assertNotEquals(ChannelGrouping.key(us), ChannelGrouping.key(au))
        assertEquals("ESPN.us", current.getValue(SplitScreenPolicy.logicalKey(us)).epgChannelId)
        assertEquals("ESPN.au", current.getValue(SplitScreenPolicy.logicalKey(au)).epgChannelId)
        val car = channels.single { it.id == 4020L }
        assertNull(current[SplitScreenPolicy.logicalKey(car)])
        assertEquals(groups.size, SplitScreenPolicy.choices(channels, null).size)
        val aliasRows = root.getValue("aliases").jsonArray.map { it.jsonObject }
        val feeds = aliasRows.groupBy { it.getValue("feedId").jsonPrimitive.long }.map { (id, rows) ->
            EpgFeed(id, "Anonymous", enabled = rows.first().getValue("enabled").jsonPrimitive.boolean,
                providerSourceId = rows.first().text("providerSourceId")?.toLong())
        }
        val aliases = aliasRows.map { row -> EpgChannelAlias(row.getValue("feedId").jsonPrimitive.long,
            row.text("guideId")!!, row.text("name")!!, "reconstructed") }
        val coverage = aliasRows.map { row ->
            val currentRows = row.getValue("currentRows").jsonPrimitive.int
            val upcoming = row.getValue("upcomingRows").jsonPrimitive.int
            GuideCoverageRow(feedId = row.getValue("feedId").jsonPrimitive.long, epgChannelId = row.text("guideId")!!,
                totalRows = row.getValue("totalRows").jsonPrimitive.int, currentRows = currentRows, upcomingRows = upcoming,
                visibleRows = currentRows + upcoming, lastEndMillis = if (upcoming > 0) 300L else 200L,
                nextStartMillis = if (upcoming > 0) 200L else null)
        }
        val started = System.nanoTime()
        val audited = GuideCoverageAudit.audit(channels, feeds, aliases, coverage, false)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(channels.size, audited.size)
        val channelById = channels.associateBy { it.id }
        audited.forEach { row ->
            val c = channelById.getValue(row.channelId)
            assertTrue(row.logicalGuideId == null || row.logicalGuideId in c.epgCandidates)
            assertFalse(row.storedMatchDisagrees)
        }
        val report = buildJsonObject {
            put("variants", channels.size); put("safeGroups", groups.size)
            put("unresolvedBorrowers", borrowed); put("auditPcMillis", elapsed)
            put("evidence", "Historical safe metadata and synthetic programme markers; not live broadcast proof")
            put("rows", JsonArray(audited.map { row -> buildJsonObject {
                put("channelId", row.channelId)
                put("guideId", row.logicalGuideId)
                put("splitGuideId", current[SplitScreenPolicy.logicalKey(channelById.getValue(row.channelId))]?.epgChannelId)
                put("identityReason", row.identityReason)
                put("storedMatchDisagrees", row.storedMatchDisagrees)
            } }))
        }
        System.getenv("OPENTV_GUIDE_GROUPING_REPORT")?.takeIf(String::isNotBlank)?.let {
            File(it).writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report))
        }
        println("Saved variants=${channels.size}; safe groups=${groups.size}; unresolved borrowers=$borrowed; PC audit=${elapsed}ms")
    }
}
