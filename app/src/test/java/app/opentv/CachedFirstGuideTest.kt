package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.ChannelGrouping
import app.opentv.data.repo.chooseGuideCandidate
import app.opentv.ui.channels.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CachedFirstGuideTest {
    private fun channel(id: Long, name: String = "US: Channel $id", guide: String? = "guide-$id"): Channel {
        val n = ChannelNameNormalizer.normalize(name)
        return Channel(id = id, sourceId = 1, streamId = "synthetic-$id", name = name,
            groupKey = n.groupKey, qualityRank = n.qualityRank, qualityLabel = n.qualityLabel,
            categoryId = null, logoUrl = null, epgChannelId = null, matchedEpgId = guide,
            number = null, streamUrl = "not-exported")
    }
    private fun programme(id: String = "guide-1", title: String = "Synthetic") = Programme(1, id, 100, 200, title)

    @Test fun `saved channels are selectable before delayed programme query finishes`() = runTest {
        val states = mutableListOf<GuideRowsState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cachedFirstGuideRows(flowOf(listOf(channel(1))), flow {
                kotlinx.coroutines.delay(10_000)
                emit(GuideProgrammeSnapshot(mapOf("guide-1" to listOf(programme())), true))
            }, now = { 150 }).collect { states += it }
        }
        runCurrent()
        assertTrue(states.last().catalogueReady)
        assertTrue(states.last().listingsLoading)
        assertEquals(1L, states.last().rows.single().primary.id)
        assertNull(states.last().rows.single().now)
        val key = states.last().rows.single().key
        advanceTimeBy(10_000); runCurrent()
        assertFalse(states.last().listingsLoading)
        assertEquals("Synthetic", states.last().rows.single().now?.title)
        assertEquals(key, states.last().rows.single().key)
        job.cancelAndJoin()
    }

    @Test fun `empty loaded guide is completed not an endless loading state`() = runTest {
        val states = cachedFirstGuideRows(flowOf(listOf(channel(1))),
            flowOf(GuideProgrammeSnapshot(loaded = true)), now = { 150 }).toList()
        assertFalse(states.last().listingsLoading)
        assertFalse(states.last().failed)
        assertTrue(states.last().catalogueReady)
        assertEquals(1, states.last().rows.size)
    }

    @Test fun `empty favourites category or source is a completed empty view`() = runTest {
        val first = cachedFirstGuideRows(flowOf(emptyList()), flowOf(GuideProgrammeSnapshot())).first()
        assertTrue(first.catalogueReady)
        assertFalse(first.listingsLoading)
        assertTrue(first.rows.isEmpty())
    }

    @Test fun `listing updates retain visible data and stable keys until replacement is ready`() = runTest {
        val windows = MutableStateFlow(GuideProgrammeSnapshot(mapOf("guide-1" to listOf(programme())), true))
        val states = mutableListOf<GuideRowsState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cachedFirstGuideRows(flowOf(listOf(channel(1))), windows, now = { 150 }).collect { states += it }
        }
        runCurrent()
        val first = states.last().rows.single()
        windows.value = GuideProgrammeSnapshot()
        runCurrent()
        assertTrue(states.last().listingsLoading)
        assertEquals(first, states.last().rows.single())
        windows.value = GuideProgrammeSnapshot(mapOf("guide-1" to listOf(programme(title = "Replacement"))), true)
        runCurrent()
        assertEquals("Replacement", states.last().rows.single().now?.title)
        assertEquals(first.key, states.last().rows.single().key)
        job.cancelAndJoin()
    }

    @Test fun `new channel source or override snapshot cannot keep old identities or listings`() = runTest {
        val original = channel(1)
        val channels = MutableStateFlow(listOf(original))
        val windows = MutableStateFlow(GuideProgrammeSnapshot(mapOf("guide-1" to listOf(programme())), true))
        val states = mutableListOf<GuideRowsState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cachedFirstGuideRows(channels, windows, now = { 150 }).collect { states += it }
        }
        runCurrent()
        channels.value = listOf(original.copy(sourceId = 2, matchedEpgId = "different", epgOverrideId = "manual"))
        runCurrent()
        assertEquals(2L, states.last().rows.single().primary.sourceId)
        assertNull(states.last().rows.single().now)
        assertNotEquals(ChannelGrouping.key(original), states.last().rows.single().key)
        channels.value = emptyList()
        runCurrent()
        assertTrue(states.last().rows.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `unchanged snapshots preserve prior guide on returning but changed metadata rejects seed`() = runTest {
        val c = channel(1)
        val prior = GuideRowPlan(listOf(c)).populate(mapOf("guide-1" to listOf(programme())), 150)
        val unchanged = cachedFirstGuideRows(flowOf(listOf(c.copy())), flowOf(GuideProgrammeSnapshot()),
            now = { 150 }, previousRows = prior).first()
        assertSame(prior, unchanged.rows)
        val changed = cachedFirstGuideRows(flowOf(listOf(c.copy(hidden = true))), flowOf(GuideProgrammeSnapshot()),
            now = { 150 }, previousRows = prior).first()
        assertNull(changed.rows.single().now)
        assertTrue(changed.rows.single().primary.hidden)
    }

    @Test fun `programme query errors retain selectable channels and stop loading`() = runTest {
        val states = cachedFirstGuideRows(flowOf(listOf(channel(1))), flow<GuideProgrammeSnapshot> {
            throw IllegalStateException("synthetic")
        }).toList()
        assertTrue(states.last().failed)
        assertFalse(states.last().listingsLoading)
        assertEquals(1, states.last().rows.size)
    }

    @Test fun `hot programme failure markers preserve populated rows and can recover`() = runTest {
        val windows = MutableStateFlow(GuideProgrammeSnapshot(mapOf("guide-1" to listOf(programme())), true))
        val states = mutableListOf<GuideRowsState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cachedFirstGuideRows(flowOf(listOf(channel(1))), windows, now = { 150 }).collect { states += it }
        }
        runCurrent()
        windows.value = GuideProgrammeSnapshot(failed = true); runCurrent()
        assertTrue(states.last().failed)
        assertFalse(states.last().listingsLoading)
        assertNotNull(states.last().rows.single().now)
        windows.value = GuideProgrammeSnapshot(loaded = true); runCurrent()
        assertFalse(states.last().failed)
        assertNull(states.last().rows.single().now)
        job.cancelAndJoin()
    }

    @Test fun `catalogue failure is explicit not an endless spinner`() = runTest {
        val states = cachedFirstGuideRows(flow<List<Channel>> { throw IllegalStateException("synthetic") },
            flowOf(GuideProgrammeSnapshot())).toList()
        assertTrue(states.last().failed)
        assertFalse(states.last().catalogueReady)
        assertFalse(states.last().listingsLoading)
    }

    @Test fun `cancellation is propagated and preparation and population cooperate`() = runTest {
        try {
            cachedFirstGuideRows(flow<List<Channel>> { throw CancellationException("synthetic") },
                flowOf(GuideProgrammeSnapshot())).toList()
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        val channels = (1L..300L).map { channel(it) }
        var checks = 0
        try {
            GuideRowPlan(channels) { if (++checks == 2) throw CancellationException("synthetic") }
            fail("Preparation did not check cancellation")
        } catch (_: CancellationException) { }
        val plan = GuideRowPlan(channels)
        try {
            plan.populate(emptyMap(), 150) { throw CancellationException("synthetic") }
            fail("Population did not check cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun `latest selected catalogue wins and no old rows return after filter switch`() = runTest {
        val selected = MutableStateFlow(1L)
        val states = mutableListOf<GuideRowsState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            selected.flatMapLatest { id ->
                cachedFirstGuideRows(flowOf(listOf(channel(id))), flow {
                    kotlinx.coroutines.delay(1000)
                    emit(GuideProgrammeSnapshot(mapOf("guide-$id" to listOf(programme("guide-$id"))), true))
                }, now = { 150 }).onStart { emit(GuideRowsState()) }
            }.collect { states += it }
        }
        runCurrent()
        selected.value = 2; runCurrent()
        val afterSwitch = states.size
        advanceTimeBy(2000); runCurrent()
        assertEquals(2L, states.last().rows.single().primary.id)
        assertTrue(states.drop(afterSwitch).flatMap { it.rows }.all { it.primary.id == 2L })
        job.cancelAndJoin()
    }

    @Test fun `all saved rows retain approved choices variants and stable focus keys`() {
        val path = System.getenv("OPENTV_GUIDE_IDENTITY_FIXTURE")
        assumeTrue("Provide safe saved catalogue", !path.isNullOrBlank())
        val root = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        val channels = root.getValue("channels").jsonArray.map { it.jsonObject }.map { r ->
            channel(r.getValue("channelId").jsonPrimitive.long, r.text("name")!!, r.text("matchedId"))
                .copy(sourceId = r.getValue("sourceId").jsonPrimitive.long,
                    epgChannelId = r.text("providerId"), epgOverrideId = r.text("overrideId"))
        }
        val programmes = root.getValue("aliases").jsonArray.map { it.jsonObject }
            .filter { it.getValue("enabled").jsonPrimitive.boolean && it.getValue("currentRows").jsonPrimitive.int > 0 }
            .map { programme(it.text("guideId")!!) }.groupBy { it.epgChannelId }
        val plan = GuideRowPlan(channels)
        val filled = plan.populate(programmes, 150)
        val expected = ChannelGrouping.groups(channels)
        assertEquals(6581, channels.size)
        assertEquals(expected.size, plan.channelsOnly.size)
        assertEquals(plan.channelsOnly.map { it.key }, filled.map { it.key })
        filled.forEach { row ->
            assertEquals(expected.getValue(row.key).sortedByDescending { it.qualityRank }, row.allVariants)
            assertEquals(chooseGuideCandidate(row.allVariants, programmes, 150), row.now?.epgChannelId)
            assertEquals(ChannelGrouping.key(row.primary), row.key)
            if (row.primary.epgCandidates.isEmpty()) assertNull(row.now)
        }
        println("Cached-first saved replay: ${channels.size} channels/${filled.size} safe groups; zero key/choice differences")
    }
}
