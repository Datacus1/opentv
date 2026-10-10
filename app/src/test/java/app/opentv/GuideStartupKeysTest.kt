package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.ChannelGrouping
import app.opentv.data.repo.chooseGuideCandidate
import app.opentv.ui.ChannelsViewModel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GuideStartupKeysTest {
    private fun channel(id: Long, name: String, guide: String? = null): Channel {
        val normal = ChannelNameNormalizer.normalize(name)
        return Channel(id = id, sourceId = 1, streamId = "synthetic-$id", name = name,
            groupKey = normal.groupKey, qualityRank = normal.qualityRank, qualityLabel = normal.qualityLabel,
            categoryId = null, logoUrl = null, epgChannelId = null, matchedEpgId = guide,
            number = null, streamUrl = "not-exported")
    }

    private fun row(c: Channel, keys: ChannelGrouping.PreparedKeys? = null) =
        ChannelsViewModel.Row(c, listOf(c), null, null, emptyList(),
            preparedKey = keys?.preparedKey(c))

    @Test fun `prepared groups and keys preserve every identity boundary`() {
        val base = channel(1, "US: ESPN HD", "ESPN.us")
        val input = listOf(base, base.copy(id = 2, sourceId = 2),
            channel(3, "AU: ESPN HD", "ESPN.au"), base.copy(id = 4, matchedEpgId = "ESPN.us2"),
            base.copy(id = 5, epgOverrideId = "manual"), base.copy(id = 6, matchedEpgId = null),
            channel(7, "CAR: Cartoon Network").copy(epgChannelId = "conflicted"),
            channel(8, "US: FOX Soccer+ HD", "FoxSoccerPlus.us"),
            channel(9, "US: Fox Soccer Plus FHD", "FoxSoccerPlus.us"),
            channel(10, "US: Fox Soccer HD", "FoxSoccer.us"),
            channel(11, "Untyped event").copy(qualityRank = 400, epgChannelId = "pending"),
            channel(12, "Untyped event").copy(epgChannelId = "pending"))
        val keys = ChannelGrouping.PreparedKeys(input)
        assertEquals(ChannelGrouping.groups(input), keys.groups(input))
        for (c in input) {
            assertEquals(ChannelGrouping.key(c), keys.key(c))
            assertEquals(ChannelGrouping.variantsFor(c, input), keys.variantsFor(c, input))
            assertEquals(ChannelGrouping.key(c), row(c, keys).key)
        }
    }

    @Test fun `copied same-id objects never reuse stale source name match override or UHD keys`() {
        val original = channel(1, "US: ESPN HD", "ESPN.us")
        val keys = ChannelGrouping.PreparedKeys(listOf(original))
        val changed = listOf(original.copy(sourceId = 2), original.copy(name = "AU: ESPN HD"),
            original.copy(matchedEpgId = "ESPN.au"), original.copy(epgOverrideId = "manual"),
            original.copy(matchedEpgId = null, epgChannelId = "pending", qualityRank = 400))
        for (c in changed) {
            assertEquals(ChannelGrouping.key(c), keys.key(c))
            assertEquals(ChannelGrouping.key(c), row(original, keys).copy(primary = c).key)
            assertEquals(ChannelGrouping.key(c), keys.preparedKey(c).keyFor(c))
        }
        assertEquals(1, keys.size)
        assertNotSame(keys.preparedKey(original), keys.preparedKey(original.copy()))
    }

    @Test fun `cache size is bounded by original object set and misses do not accumulate`() {
        val original = channel(1, "Unknown")
        val input = mutableListOf(original, original)
        val keys = ChannelGrouping.PreparedKeys(input)
        input.clear()
        repeat(1000) {
            val missing = original.copy(id = it + 2L, matchedEpgId = "new-$it")
            assertEquals(ChannelGrouping.key(missing), keys.key(missing))
            keys.preparedKey(missing)
        }
        assertEquals(1, keys.size)
        assertSame(keys.preparedKey(original), keys.preparedKey(original))
        assertEquals(ChannelGrouping.key(original), keys.key(original))
    }

    @Test fun `independent builds cannot contaminate the next build`() {
        val old = channel(1, "US: ESPN HD", "ESPN.us")
        val updated = old.copy(matchedEpgId = "ESPN.us2")
        val before = ChannelGrouping.PreparedKeys(listOf(old))
        val after = ChannelGrouping.PreparedKeys(listOf(updated))
        assertEquals(ChannelGrouping.key(old), before.key(old))
        assertEquals(ChannelGrouping.key(updated), after.key(updated))
        assertEquals(after.key(updated), before.key(updated))
        assertEquals(before.key(old), after.key(old))
        assertNotEquals(before.key(old), after.key(updated))
    }

    @Test fun `mixed and missing-index candidates remain fail-closed and preserve tie order`() {
        val primary = channel(1, "US: ESPN FHD", "empty")
        val foreign = channel(2, "AU: ESPN FHD", "live")
        val duplicate = primary.copy(id = 3)
        val input = listOf(primary, foreign, duplicate)
        val programmes = mapOf("live" to listOf(Programme(1, "live", 100, 200, "Synthetic")))
        for (cached in listOf(emptyList(), listOf(foreign), input)) {
            val keys = ChannelGrouping.PreparedKeys(cached)
            for (order in listOf(input, input.reversed(), emptyList())) {
                assertEquals(chooseGuideCandidate(order, programmes, 150),
                    chooseGuideCandidate(order, programmes, 150, keys))
            }
            assertNull(chooseGuideCandidate(input, programmes, 150, keys))
        }
    }

    @Test fun `manual current upcoming past empty and duplicate candidates are equivalent`() {
        val c = channel(1, "US: ESPN HD", "matched").copy(epgOverrideId = "manual")
        val variants = listOf(c, c.copy(id = 2, qualityRank = 300), c.copy(id = 3))
        val keys = ChannelGrouping.PreparedKeys(variants)
        val manual = Programme(1, "manual", 200, 300, "Upcoming")
        val current = Programme(1, "matched", 100, 200, "Current")
        val past = current.copy(startUtcMillis = 0, endUtcMillis = 50)
        for (programmes in listOf(emptyMap(), mapOf("matched" to listOf(current)),
            mapOf("manual" to listOf(manual), "matched" to listOf(current)),
            mapOf("manual" to emptyList(), "matched" to listOf(past)))) {
            for (now in listOf(25L, 150L, 250L, 350L)) {
                assertEquals(chooseGuideCandidate(variants, programmes, now),
                    chooseGuideCandidate(variants, programmes, now, keys))
            }
        }
    }

    @Test fun `prepared rows retain equality across unchanged rebuilds and safe copy semantics`() {
        val c = channel(1, "US: ESPN HD", "ESPN.us")
        val first = row(c, ChannelGrouping.PreparedKeys(listOf(c)))
        val copy = c.copy()
        val rebuilt = row(copy, ChannelGrouping.PreparedKeys(listOf(copy)))
        assertEquals(first, rebuilt)
        assertEquals(first.hashCode(), rebuilt.hashCode())
        assertEquals(first, first.copy())
        assertEquals(first.key, first.copy(primary = copy).key)
        val changed = c.copy(epgOverrideId = "manual")
        assertEquals(ChannelGrouping.key(changed), first.copy(primary = changed).key)
        assertNotEquals(first.key, first.copy(primary = changed).key)
    }

    /** Safe saved names/IDs only; all programmes below are synthetic, with no HTTP/DB/device use. */
    @Test fun `all saved catalogue groups choices and row keys are equivalent with less work`() {
        val path = System.getenv("OPENTV_GUIDE_IDENTITY_FIXTURE")
        assumeTrue("Provide safe local snapshot", !path.isNullOrBlank())
        val root = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        val channels = root.getValue("channels").jsonArray.map { it.jsonObject }.map { r ->
            channel(r.getValue("channelId").jsonPrimitive.long, r.text("name")!!, r.text("matchedId"))
                .copy(sourceId = r.getValue("sourceId").jsonPrimitive.long,
                    epgChannelId = r.text("providerId"), epgOverrideId = r.text("overrideId"))
        }
        assertEquals(6581, channels.size)
        val programmes = root.getValue("aliases").jsonArray.map { it.jsonObject }
            .filter { it.getValue("enabled").jsonPrimitive.boolean }
            .flatMap { r ->
                val id = r.text("guideId")!!
                buildList {
                    if (r.getValue("currentRows").jsonPrimitive.int > 0) add(Programme(1, id, 100, 200, "Synthetic current"))
                    if (r.getValue("upcomingRows").jsonPrimitive.int > 0) add(Programme(1, id, 200, 300, "Synthetic upcoming"))
                    if (r.getValue("totalRows").jsonPrimitive.int > 0) add(Programme(1, id, 0, 50, "Synthetic past"))
                }
            }.groupBy { it.epgChannelId }
        val before = ChannelGrouping.groups(channels)
        val keys = ChannelGrouping.PreparedKeys(channels)
        assertEquals(6581, keys.size)
        assertEquals(before, keys.groups(channels))
        for (c in channels) assertEquals(ChannelGrouping.key(c), keys.key(c))
        for (group in before.values) {
            for (now in listOf(25L, 150L, 250L, 350L)) {
                assertEquals(chooseGuideCandidate(group, programmes, now),
                    chooseGuideCandidate(group, programmes, now, keys))
            }
            val primary = group.maxBy { it.qualityRank }
            assertEquals(ChannelGrouping.key(primary), row(primary, keys).key)
        }
        // Alternate baseline/optimized runs so JIT warm-up/order is not mistaken for improvement.
        fun run(prepared: Boolean): Pair<Long, List<Pair<String, String?>>> {
            val start = System.nanoTime()
            val index = if (prepared) ChannelGrouping.PreparedKeys(channels) else null
            val groups = index?.groups(channels) ?: ChannelGrouping.groups(channels)
            val result = groups.values.map { group ->
                val primary = group.maxBy { it.qualityRank }
                val key = row(primary, index).key
                val choice = if (index == null) chooseGuideCandidate(group, programmes, 150)
                    else chooseGuideCandidate(group, programmes, 150, index)
                key to choice
            }
            return (System.nanoTime() - start) to result
        }
        val baseline = mutableListOf<Long>()
        val optimized = mutableListOf<Long>()
        repeat(7) { iteration ->
            val a: Pair<Long, List<Pair<String, String?>>>
            val b: Pair<Long, List<Pair<String, String?>>>
            if (iteration % 2 == 0) { a = run(false); b = run(true) }
            else { b = run(true); a = run(false) }
            assertEquals(a.second, b.second)
            baseline += a.first; optimized += b.first
        }
        println("PC-only saved-catalogue replay: channels=${channels.size}; groups=${before.size}; " +
            "all keys and guide choices unchanged; baseline median7=${baseline.sorted()[3] / 1_000_000.0}ms; " +
            "prepared median7=${optimized.sorted()[3] / 1_000_000.0}ms. Not Shield/full-startup latency proof.")
    }
}
