package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.GuideCoverageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
            categoryId = null, logoUrl = null, epgChannelId = "known", number = null, streamUrl = "https://example.invalid/live")))
    }
    @After fun close() = db.close()

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
        assertEquals(1, r.syncAll(now, true).feedsFailed); assertEquals(3, db.programmes().countAll())
        assertEquals(1, requests)
        response = { xml(listOf(p("known", now))) }
        assertEquals(0, r.syncAll(now, true).feedsFailed); assertEquals(3, db.programmes().countAll())
        assertEquals("New", db.programmes().upcoming("known", now, 1).single().title)
    }
    @Test fun `global row budget includes disabled feed rows and cannot silently grow`() = runBlocking {
        db.epgFeeds().insert(EpgFeed(id = 2, name = "Disabled", enabled = false))
        db.programmes().upsertAll(listOf(p("other", now).copy(feedId = 2), p("extra", now).copy(feedId = 2)))
        response = { xml(listOf(p("known", now + 60_000))) }
        assertEquals(1, repo(allowed = { false }, perFeed = 10, total = 3).syncAll(now, true).feedsFailed)
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
    private suspend fun setupFox() {
        db.sources().insert(Source(id = 7, name = "Synthetic", kind = SourceKind.XTREAM,
            url = "https://example.invalid", username = "synthetic", password = "synthetic"))
        db.epgFeeds().update(db.epgFeeds().all().single().copy(url = null, providerSourceId = 7))
        db.channels().upsertAll((1..2).map { id -> Channel(id = id.toLong(), sourceId = 7,
            streamId = id.toString(), name = "FOX East", groupKey = "foxeast", qualityRank = 3 - id,
            categoryId = null, logoUrl = null, epgChannelId = null, number = null,
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
}
