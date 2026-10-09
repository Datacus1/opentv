package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.GuideCoverageStatus
import app.opentv.data.repo.ChannelGrouping
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.remote.StalkerApi
import app.opentv.core.AppSettings
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.recording.RecordingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Room/repository, fake HTTP and synthetic StatFs. No Shield or real provider is contacted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgGuideRecoveryIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: OpenTvDatabase
    private lateinit var http: OkHttpClient
    private val now = 1_800_000_000_000L
    private var requests = 0
    private var idle = true
    private var response: (Int) -> String = { xml(listOf(p("known", now))) }
    private val time = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun blocks(n: Int) = ShadowStatFs.registerStats(context.dataDir.absolutePath, 2_000_000, n, n)
    private fun p(id: String, start: Long, end: Long = start + 3_600_000, title: String = "New") =
        Programme(feedId = 1, epgChannelId = id, startUtcMillis = start, endUtcMillis = end, title = title)
    private fun xml(rows: List<Programme>): String = "<tv>" + rows.map { it.epgChannelId }.distinct().joinToString("") {
        "<channel id=\"$it\"><display-name>$it</display-name></channel>"
    } + rows.joinToString("") {
        "<programme channel=\"${it.epgChannelId}\" start=\"${time.format(Date(it.startUtcMillis))}\" stop=\"${time.format(Date(it.endUtcMillis))}\"><title>${it.title}</title></programme>"
    } + "</tv>"
    private fun repo(allowed: suspend () -> Boolean = { idle }, recover: suspend () -> Boolean = { false },
        perFeed: Int = EpgRepository.MAX_PROGRAMMES_PER_FEED, total: Int = EpgRepository.MAX_PROGRAMMES_TOTAL) =
        EpgRepository(context, db, db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(),
            XtreamApi(http), http, allowed, recover, perFeed, total)

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext(); blocks(786_432)
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = response(++requests)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/xml".toMediaType())).build()
        }.build()
        db.epgFeeds().insert(EpgFeed(id = 1, name = "Synthetic", url = "https://example.invalid/guide",
            lastSyncMillis = now - 7 * 3_600_000, lastResult = "Retain"))
        db.epgAliases().upsertAll(listOf(EpgChannelAlias(1, "known", "known", "known")))
        db.programmes().upsertAll(listOf(p("known", now, title = "Cached")))
        db.channels().upsertAll(listOf(Channel(id = 1, sourceId = 7, streamId = "1", name = "known", groupKey = "known",
            categoryId = null, logoUrl = null, epgChannelId = "known", matchedEpgId = "known", number = null, streamUrl = "https://example.invalid/live")))
    }
    @After fun close() = db.close()

    @Test fun `matcher summary excludes withdrawn stale match on its first pass and keeps manual override`() = runBlocking {
        val original = db.channels().allForMatching().single()
        db.channels().upsertAll(listOf(original.copy(name = "Unrelated", epgChannelId = null)))
        assertEquals(0, repo().runMatcher().first)
        assertNull(db.channels().allForMatching().single().matchedEpgId)
        db.channels().upsertAll(listOf(original.copy(name = "Unrelated", epgChannelId = null,
            epgOverrideId = "known")))
        assertEquals(1, repo().runMatcher().first)
        val row = db.channels().allForMatching().single()
        assertNull(row.matchedEpgId); assertEquals("known", row.epgOverrideId)
    }

    @Test fun `PLUS grouping repair separates base joins symbol variants and preserves every unrelated field`() = runBlocking {
        val original = db.channels().allForMatching().single().copy(id = 2, streamId = "plus",
            name = "US: MeTV PLUS HD", displayName = "MeTV", groupKey = "metv", qualityRank = 0,
            epgChannelId = "MeTVplus.us", matchedEpgId = "known", epgOverrideId = "manual",
            customName = "My Plus", favourite = true, hidden = true, sortIndex = 91,
            tvArchive = true, tvArchiveDays = 7, lastSeenMillis = 1234)
        val symbolic = original.copy(id = 3, streamId = "symbol", name = "US: MeTV + SD",
            displayName = "MeTV +", groupKey = "metvplus", qualityRank = 100, qualityLabel = "SD")
        val base = original.copy(id = 4, streamId = "base", name = "US: MeTV HD", epgChannelId = "MeTV.us")
        db.channels().upsertAll(listOf(original, symbolic, base))
        db.epgAliases().upsertAll(listOf(EpgChannelAlias(1, "MeTVplus.us", "MeTV PLUS", "metvplus"),
            EpgChannelAlias(1, "MeTV.us", "MeTV", "metv")))
        db.programmes().upsertAll(listOf(p("MeTVplus.us", now), p("MeTV.us", now)))
        repo().runMatcher()
        val repaired = db.channels().allForMatching().first { it.id == 2L }
        val normalized = app.opentv.data.parser.ChannelNameNormalizer.normalize(original.name)
        assertEquals(original.copy(displayName = normalized.baseName, groupKey = normalized.groupKey,
            qualityRank = normalized.qualityRank, qualityLabel = normalized.qualityLabel,
            matchedEpgId = "MeTVplus.us"), repaired)
        val rows = db.channels().allForMatching().associateBy { it.id }
        assertEquals(rows[3]!!.groupKey, repaired.groupKey)
        assertNotEquals(rows[4]!!.groupKey, repaired.groupKey)
        val beforeSecondPass = db.channels().allForMatching()
        repo().runMatcher()
        assertEquals(beforeSecondPass, db.channels().allForMatching())
        assertEquals(0, requests)
    }

    @Test fun `low reserve stays read only when recovery cannot restore space`() = runBlocking {
        blocks(262_144); var attempts = 0
        val r = repo(recover = { attempts++; false })
        val before = db.epgFeeds().all()
        val result = r.syncAll(now, true)
        assertTrue(result.storageDeferred); assertTrue(r.health.value.storageBlocked)
        assertEquals(1, attempts); assertEquals(0, requests); assertEquals(before, db.epgFeeds().all())
        assertEquals("Cached", db.programmes().upcoming("known", now, 1).single().title)
    }
    @Test fun `recovered actual space enables the next refresh without clearing guide`() = runBlocking {
        blocks(262_144)
        val r = repo(recover = { blocks(786_432); true })
        val result = r.syncAll(now, true)
        assertFalse(result.storageDeferred); assertEquals(2, requests); assertFalse(r.health.value.storageBlocked)
        assertEquals("New", db.programmes().upcoming("known", now, 1).single().title)
    }
    @Test fun `platform success without physical reserve cannot authorize database writes`() = runBlocking {
        blocks(262_144)
        assertTrue(repo(recover = { true }).syncAll(now, true).storageDeferred)
        assertEquals(0, requests); assertEquals("Retain", db.epgFeeds().all().single().lastResult)
    }
    @Test fun `bulk second request losing storage preserves aliases and feed metadata`() = runBlocking {
        response = { n -> if (n == 2) blocks(262_144); xml(listOf(p("known", now))) }
        val before = db.epgFeeds().all().single()
        val result = repo(allowed = { false }).syncAll(now, true)
        assertTrue(result.storageDeferred); assertEquals(2, requests)
        assertEquals(before, db.epgFeeds().all().first { it.id == 1L })
        assertEquals(1, db.epgAliases().count())
        assertEquals("Cached", db.programmes().upcoming("known", now, 1).single().title)
    }
    @Test fun `changed snapshot cannot reconcile cached identities or replace aliases`() = runBlocking {
        db.programmes().upsertAll(listOf(p("retired", now, title = "Keep")))
        response = { n -> xml(if (n == 1) listOf(p("known", now), p("new-channel", now)) else listOf(p("known", now))) }
        val result = repo(allowed = { false }).syncAll(now, true)
        assertEquals(1, result.feedsFailed); assertEquals(1, db.epgAliases().count())
        assertEquals("Keep", db.programmes().upcoming("retired", now, 1).single().title)
        assertEquals(1, db.epgFeeds().all().first { it.id == 1L }.failureCount)
    }
    @Test fun `empty or malformed preflight never replaces working programme data`() = runBlocking {
        for (body in listOf("<tv></tv>", "<tv><programme")) {
            response = { body }
            assertEquals(1, repo(allowed = { false }).syncAll(now, true).feedsFailed)
            assertEquals("Cached", db.programmes().upcoming("known", now, 1).single().title)
            assertEquals(1, db.epgAliases().count())
        }
    }
    @Test fun `preflight cancellation propagates and clears only refreshing flag`() = runBlocking {
        response = { throw CancellationException("Synthetic") }
        val r = repo(allowed = { false })
        try { r.syncAll(now, true); fail("Canceled request swallowed") } catch (_: CancellationException) { }
        assertFalse(r.health.value.refreshing)
        assertEquals("Retain", db.epgFeeds().all().first { it.id == 1L }.lastResult)
    }
    @Test fun `offline maintenance bounded to eight batches and resumes remaining retention`() = runBlocking {
        db.programmes().upsertAll((0 until 12000).map { p("expired", now - 3 * 86_400_000L + it * 1000L) } +
            p("far-future", now + 7 * 86_400_000L))
        val r = repo()
        assertTrue(r.maintainCache(now)); assertEquals(4002, db.programmes().countAll())
        assertTrue(r.maintainCache(now)); assertEquals(1, db.programmes().countAll())
        assertEquals(0, requests); assertEquals("Retain", db.epgFeeds().all().single().lastResult)
    }
    @Test fun `active playback or recording pauses all database cleanup`() = runBlocking {
        db.programmes().upsertAll(listOf(p("expired", now - 3 * 86_400_000L)))
        idle = false
        assertTrue(repo().maintainCache(now)); assertEquals(2, db.programmes().countAll()); assertEquals(0, requests)
    }
    @Test fun `below download reserve idle retirement is bounded without feed writes or HTTP`() = runBlocking {
        db.programmes().upsertAll((0 until 650).map { p("expired", now - 3 * 86_400_000L + it * 1000L) })
        blocks(300_000) // 1.144 GiB: above hard + 32 MiB, below download reserve.
        val before = db.epgFeeds().all()
        val r = repo()
        assertFalse(r.maintainCache(now)); assertEquals(251, db.programmes().countAll())
        assertFalse(r.maintainCache(now)); assertEquals(1, db.programmes().countAll())
        assertEquals(before, db.epgFeeds().all()); assertEquals(0, requests)
        assertEquals("Cached", db.programmes().upcoming("known", now, 1).single().title)
        assertTrue(r.health.value.storageBlocked)
    }
    @Test fun `insufficient recovery headroom or active playback leaves expired rows intact`() = runBlocking {
        db.programmes().upsertAll(listOf(p("expired", now - 3 * 86_400_000L)))
        for (n in listOf(262_144, 290_000)) {
            blocks(n); assertFalse(repo().maintainCache(now)); assertEquals(2, db.programmes().countAll())
        }
        blocks(300_000); idle = false
        assertFalse(repo().maintainCache(now)); assertEquals(2, db.programmes().countAll())
    }
    @Test fun `falling reserve stops recovery before the next batch`() = runBlocking {
        db.programmes().upsertAll((0 until 650).map { p("expired", now - 3 * 86_400_000L + it * 1000L) })
        blocks(300_000); var checks = 0
        repo(allowed = { if (++checks == 3) blocks(262_144); true }).maintainCache(now)
        assertEquals(551, db.programmes().countAll()); assertEquals(0, requests)
    }
    @Test fun `low reserve refresh retires expired rows but retains current data and metadata`() = runBlocking {
        db.programmes().upsertAll(listOf(p("expired", now - 3 * 86_400_000L)))
        blocks(300_000); val before = db.epgFeeds().all()
        assertTrue(repo().syncAll(now, true).storageDeferred)
        assertEquals(1, db.programmes().countAll()); assertEquals(before, db.epgFeeds().all())
        assertEquals(0, requests)
    }
    @Test fun `rolling month of same key replacements and expiry stays bounded`() = runBlocking {
        val r = repo()
        repeat(30) { day ->
            val clock = now + day * 86_400_000L
            val rows = (0 until 24).map { p("rolling", clock + it * 3_600_000L) }
            db.programmes().upsertAll(rows)
            db.programmes().upsertAll(rows.map { it.copy(title = "Corrected") })
            assertTrue(r.maintainCache(clock))
            assertTrue(db.programmes().countAll() <= 49)
        }
        assertEquals(0, requests)
    }
    @Test fun `starting playback during cleanup stops at a bounded batch`() = runBlocking {
        db.programmes().upsertAll((0 until 1500).map { p("expired", now - 3 * 86_400_000L + it * 1000L) })
        var checks = 0
        repo(allowed = { ++checks <= 3 }).maintainCache(now)
        assertTrue(db.programmes().countAll() in 501..1501)
        assertEquals("Cached", db.programmes().upcoming("known", now, 1).single().title)
    }
    @Test fun `removed feed tombstone cleanup completes offline while preserving active feed`() = runBlocking {
        db.epgFeeds().insert(EpgFeed(id = 2, name = "Removed", deleting = true, enabled = false))
        db.programmes().upsertAll(listOf(p("removed", now).copy(feedId = 2)))
        db.epgAliases().upsertAll(listOf(EpgChannelAlias(2, "removed", "Removed", "removed")))
        repo().maintainCache(now)
        assertEquals(listOf(1L), db.epgFeeds().all().map { it.id })
        assertEquals(1, db.programmes().countAll()); assertEquals(1, db.epgAliases().count()); assertEquals(0, requests)
    }
    @Test fun `full row budget defers new identities but permits same identity update`() = runBlocking {
        db.programmes().upsertAll(listOf(p("other", now), p("extra", now)))
        response = { xml(listOf(p("known", now + 60_000))) }
        val r = repo(allowed = { false }, perFeed = 3, total = 3)
        val deferred = r.syncAll(now, true)
        assertEquals(0, deferred.feedsFailed); assertTrue(deferred.retryNeeded)
        assertEquals(0, db.epgFeeds().all().first { it.id == 1L }.failureCount)
        assertEquals(3, db.programmes().countAll())
        assertEquals(1, requests)
        response = { xml(listOf(p("known", now))) }
        assertEquals(0, r.syncAll(now, true).feedsFailed); assertEquals(3, db.programmes().countAll())
        assertEquals("New", db.programmes().upcoming("known", now, 1).single().title)
    }
    @Test fun `global row budget includes disabled feed rows and cannot silently grow`() = runBlocking {
        db.epgFeeds().insert(EpgFeed(id = 2, name = "Disabled", enabled = false))
        db.programmes().upsertAll(listOf(p("other", now).copy(feedId = 2), p("extra", now).copy(feedId = 2)))
        response = { xml(listOf(p("known", now + 60_000))) }
        val deferred = repo(allowed = { false }, perFeed = 10, total = 3).syncAll(now, true)
        assertEquals(0, deferred.feedsFailed); assertTrue(deferred.retryNeeded)
        assertEquals(3, db.programmes().countAll()); assertEquals(1, requests)
    }
    @Test fun `audit checks every channel variant across multiple pages without writing database`() = runBlocking {
        db.channels().upsertAll((2..405).map { id -> Channel(id = id.toLong(), sourceId = 7,
            streamId = id.toString(), name = "Unknown $id", groupKey = "unknown$id", categoryId = null,
            logoUrl = null, epgChannelId = null, number = null, streamUrl = "https://example.invalid/private") })
        val before = db.epgFeeds().all(); val rows = repo().auditChannels(now)
        assertEquals(405, rows.size); assertEquals(GuideCoverageStatus.ACTUAL_CURRENT, rows.first().status)
        val pages = (0 until 405 step 200).flatMap { app.opentv.data.repo.GuideCoverageAudit.page(rows, it, 200).rows }
        assertEquals(405, pages.map { it.channelId }.toSet().size)
        assertEquals(before, db.epgFeeds().all()); assertEquals(0, requests)
    }
    @Test fun `capacity deferral survives repository restart and retries once actual room appears`() = runBlocking {
        db.programmes().upsertAll(listOf(p("expired", now - 3 * 86_400_000L)))
        response = { xml(listOf(p("new", now))) }
        assertTrue(repo(allowed = { false }, perFeed = 2, total = 2).syncAll(now, true).retryNeeded)
        assertEquals(1, requests); assertEquals(0, db.epgFeeds().all().first { it.id == 1L }.failureCount)
        val r = repo(allowed = { false }, perFeed = 2, total = 2)
        assertTrue(r.syncAll(now + 1000).retryNeeded); assertEquals(1, requests)
        repo(perFeed = 2, total = 2).maintainCache(now + 1000)
        assertTrue(r.hasResolvedCapacity())
        assertEquals(0, r.syncAll(now + 1000).feedsFailed); assertEquals(3, requests)
        assertFalse(r.hasResolvedCapacity())
        r.syncAll(now + 2000); assertEquals(3, requests)
    }
    @Test fun `legacy capacity failure bypasses obsolete backoff but genuine network failure does not`() = runBlocking {
        val feed = db.epgFeeds().all().single()
        db.epgFeeds().update(feed.copy(lastAttemptMillis = now, failureCount = 7,
            lastResult = app.opentv.data.repo.GuideCapacityDeferral.LEGACY))
        assertEquals(0, repo().syncAll(now + 1000).feedsFailed); assertEquals(2, requests)
        db.epgFeeds().update(feed.copy(lastAttemptMillis = now, failureCount = 7,
            lastResult = "Download failed (IOException); cached listings retained."))
        repo().maintainCache(now + 1000)
        assertFalse(repo().hasResolvedCapacity())
        repo().syncAll(now + 1000); assertEquals(2, requests)
        assertEquals(7, db.epgFeeds().all().first { it.id == 1L }.failureCount)
    }
    @Test fun `capacity resolved followed by real HTTP failure reinstates ordinary backoff`() = runBlocking {
        db.epgFeeds().update(db.epgFeeds().all().single().copy(lastAttemptMillis = now,
            lastResult = app.opentv.data.repo.GuideCapacityDeferral.encode(1)))
        response = { throw java.io.IOException("Synthetic private transport detail") }
        assertEquals(1, repo().syncAll(now + 1000).feedsFailed)
        assertEquals(1, requests); assertFalse(repo().hasResolvedCapacity())
        repo().maintainCache(now + 2000); repo().syncAll(now + 2000)
        assertEquals(1, requests)
    }
    @Test fun `identity preflight handles more than 250 keys at full global capacity`() = runBlocking {
        val rows = listOf(p("known", now)) + (0 until 250).flatMap { id ->
            listOf(p("channel-$id", now), p("channel-$id", now + 3_600_000))
        }
        db.programmes().upsertAll(rows)
        response = { xml(rows) }
        val result = repo(allowed = { false }, perFeed = 1000, total = 501).syncAll(now, true)
        assertEquals(0, result.feedsFailed); assertTrue(result.programmesWritten > 250)
        assertEquals(501, db.programmes().countAll()); assertEquals(2, requests)
    }
    @Test fun `read only audit does not wait behind a network refresh mutex`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Synthetic test gate expired" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(xml(listOf(p("known", now))).toResponseBody("application/xml".toMediaType())).build()
        }.build()
        val r = EpgRepository(context, db, db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(),
            db.sources(), XtreamApi(client), client, maintenanceAllowed = { false }, recoverStorage = { false })
        val sync = async(Dispatchers.IO) { r.syncAll(now, true) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val rows = withTimeout(2_000) { r.auditChannels(now) }
            assertEquals(GuideCoverageStatus.ACTUAL_CURRENT, rows.single().status)
        } finally { release.countDown() }
        assertEquals(0, sync.await().feedsFailed)
    }
    private suspend fun setupFox() {
        db.sources().insert(Source(id = 7, name = "Synthetic", kind = SourceKind.XTREAM,
            url = "https://example.invalid", username = "synthetic", password = "synthetic"))
        db.epgFeeds().update(db.epgFeeds().all().single().copy(url = null, providerSourceId = 7))
        db.channels().upsertAll((1..2).map { id -> Channel(id = id.toLong(), sourceId = 7,
            streamId = id.toString(), name = "FOX East", groupKey = "foxeast", qualityRank = 3 - id,
            categoryId = null, logoUrl = null, epgChannelId = "fox-east", number = null,
            streamUrl = "https://example.invalid/live") })
    }
    @Test fun `successful second short guide variant does not leave a false failure warning`() = runBlocking {
        setupFox(); var shortRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val short = chain.request().url.queryParameter("action") == "get_short_epg"
            if (short && ++shortRequests == 1) throw java.io.IOException("Synthetic")
            val body = if (short) """{"epg_listings":[{"title":"Short listing","start_timestamp":1800000000,"stop_timestamp":1800003600}]}"""
                else xml(listOf(p("known", now)))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val r = EpgRepository(context, db, db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(),
            db.sources(), XtreamApi(client), client, maintenanceAllowed = { false }, recoverStorage = { false })
        val result = r.syncAll(now, true)
        assertEquals(2, shortRequests); assertFalse(result.retryNeeded); assertFalse(r.health.value.failed)
        assertEquals(2, db.programmes().countAll())
    }
    @Test fun `full global capacity blocks absent short guide before HTTP and keeps retry status`() = runBlocking {
        setupFox(); var shortRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.queryParameter("action") == "get_short_epg") shortRequests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(xml(listOf(p("known", now))).toResponseBody("application/xml".toMediaType())).build()
        }.build()
        val r = EpgRepository(context, db, db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(),
            db.sources(), XtreamApi(client), client, maintenanceAllowed = { false }, recoverStorage = { false },
            rowLimitPerFeed = 10, rowLimitTotal = 1)
        val result = r.syncAll(now, true)
        assertEquals(0, shortRequests); assertTrue(result.retryNeeded); assertTrue(r.health.value.failed)
        assertEquals(1, db.programmes().countAll())
    }

    @Test fun `short guide upgrade retains old cache and binds each regional or opaque request exactly`() = runBlocking {
        setupFox()
        val first = db.channels().byId(1)!!.copy(name = "US: FOX East HD", epgChannelId = null)
        val second = first.copy(id = 2, streamId = "2")
        val au = first.copy(id = 3, streamId = "3", name = "AU: FOX East HD")
        db.channels().upsertAll(listOf(first, second, au))
        val legacy = "opentv.provider-short.7.foxeast"
        db.programmes().upsertAll(listOf(p(legacy, now)))
        db.epgAliases().upsertAll(listOf(EpgChannelAlias(1, legacy, "FOX East", "foxeast")))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val short = chain.request().url.queryParameter("action") == "get_short_epg"
            val stream = chain.request().url.queryParameter("stream_id")
            val body = if (short) """{"epg_listings":[{"title":"Stream $stream","start_timestamp":1800000000,"stop_timestamp":1800003600}]}"""
                else "<tv></tv>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val r = EpgRepository(context, db, db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(),
            db.sources(), XtreamApi(client), client, maintenanceAllowed = { false }, recoverStorage = { false })
        // Existing bulk cache/failure state skips the XMLTV replacement; legacy alias coexists.
        db.epgFeeds().update(db.epgFeeds().all().single().copy(lastAttemptMillis = now, failureCount = 1))
        r.syncAll(now)
        val expected = listOf(first, second, au).map { c ->
            val id = EpgRepository.providerShortEpgId(7, "foxeast", ChannelGrouping.shortGuideKey(c))
            assertEquals(id, db.channels().byId(c.id)!!.matchedEpgId)
            assertEquals("Stream ${c.streamId}", db.programmes().upcoming(id, now, 10).single().title)
            id
        }
        assertEquals(3, expected.distinct().size)
        assertEquals(1, db.programmes().upcoming(legacy, now, 10).size) // No risky migration delete.
        assertEquals(setOf("US: FOX East HD", "AU: FOX East HD", "FOX East"),
            db.epgAliases().allForAudit().filter { it.epgId != "known" }.map { it.displayName }.toSet())
    }

    @Test fun `repository candidates include reviewed aliases without crossing source region or overrides`() = runBlocking {
        fun c(id: Long, name: String, key: String, source: Long = 7, guide: String? = "Science.us") =
            Channel(id = id, sourceId = source, streamId = "$id", name = name, groupKey = key,
                qualityRank = id.toInt(), qualityLabel = "quality-$id", categoryId = null, logoUrl = null,
                epgChannelId = null, matchedEpgId = guide, number = null, streamUrl = "not-used")
        val first = c(1, "US: Science Channel HD", "sciencechannel")
        val reviewed = c(2, "US: Science (Discovery) FHD", "sciencediscovery")
        val region = c(3, "AU: Science HD", "science", guide = "Science.au")
        val otherSource = first.copy(id = 4, sourceId = 8)
        val manual = first.copy(id = 5, epgOverrideId = "manual")
        val rejected = first.copy(id = 6, matchedEpgId = null)
        db.channels().upsertAll(listOf(first, reviewed, region, otherSource, manual, rejected))
        val catalog = CatalogRepository(db.sources(), db.channels(), db.categories(), db.movies(), db.series(),
            db.episodes(), db.positions(), XtreamApi(http), StalkerApi(http), http, AppSettings.get(context))
        assertEquals(setOf(1L, 2L), catalog.variants(first).map { it.id }.toSet())
        assertEquals(setOf(2L, 4L), catalog.recordSourceOptions(first).map { it.id }.toSet())
        assertEquals(listOf(rejected), catalog.variants(rejected))
        assertEquals(listOf(rejected), catalog.recordSourceOptions(rejected))
        assertEquals(first, db.channels().byId(1)) // Candidate lookup is read-only.
    }

    @Test fun `recording clash uses verified alternate and never substitutes a regional or rejected sibling`() = runBlocking {
        val original = Channel(id = 1, sourceId = 7, streamId = "1", name = "US: ESPN HD",
            groupKey = "espn", qualityRank = 200, categoryId = null, logoUrl = null, epgChannelId = null,
            matchedEpgId = "ESPN.us", number = null, streamUrl = "https://example.invalid/live/1.ts")
        val foreign = original.copy(id = 2, sourceId = 8, name = "AU: ESPN HD", matchedEpgId = "ESPN.au", qualityRank = 400)
        val verified = original.copy(id = 3, sourceId = 9, qualityRank = 300)
        db.channels().upsertAll(listOf(original, foreign, verified))
        db.recordings().insert(Recording(channelId = 999, sourceId = 7, channelName = "Busy", title = "Busy",
            filePath = "not-used", streamUrl = "not-used", scheduledStartMillis = now,
            scheduledEndMillis = now + 86_400_000, status = RecordingStatus.SCHEDULED))
        val recordings = RecordingRepository(db.recordings(), db.seriesRules())
        val engine = RecordingEngine(context, recordings, SourceRepository(db.sources(), XtreamApi(http), StalkerApi(http)),
            AppSettings.get(context), db.channels(), repo())
        val first = recordings.byId(engine.scheduleProgramme(original, p("ESPN.us", now + 3_600_000)))!!
        assertEquals(9L, first.sourceId)
        assertEquals(original.id, first.channelId)
        db.channels().upsertAll(listOf(verified.copy(matchedEpgId = null)))
        val second = recordings.byId(engine.scheduleProgramme(original, p("ESPN.us", now + 7_200_000)))!!
        assertEquals(7L, second.sourceId)
        assertEquals(0, requests) // Simulated scheduling only; no stream/network/service starts.
    }
}
