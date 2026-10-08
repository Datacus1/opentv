/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
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

/** Real repository + Room + fake HTTP; never contacts a provider or uses real credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgStorageDeferralTest {
    private lateinit var context: Context
    private lateinit var db: OpenTvDatabase
    private lateinit var repository: EpgRepository
    private val now = 1_800_000_000_000L
    private var requests = 0
    private var shortRequests = 0
    private var dropDuringShortRequest = false
    private var cancelDuringShortRequest = false
    private val syntheticId = EpgRepository.providerShortEpgId(1, "foxeast")

    private fun freeBlocks(blocks: Int) {
        ShadowStatFs.registerStats(context.dataDir.absolutePath, 2_000_000, blocks, blocks)
    }

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        freeBlocks(786_432) // 3 GiB free, with Robolectric's 4096-byte blocks.
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
            .allowMainThreadQueries().build()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val short = chain.request().url.queryParameter("action") == "get_short_epg"
            if (short) {
                shortRequests++
                if (cancelDuringShortRequest) throw CancellationException("cancel synthetic request")
                if (dropDuringShortRequest) freeBlocks(262_144) // 1 GiB: below hard floor.
            }
            val body = if (short) """{"epg_listings":[{"title":"New schedule","start_timestamp":1800000000,"stop_timestamp":1800003600}]}""" else "<tv></tv>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = EpgRepository(context, db, db.programmes(), db.epgFeeds(),
            db.epgAliases(), db.channels(), db.sources(), XtreamApi(http), http,
            maintenanceAllowed = { false })
        db.sources().insert(Source(id = 1, name = "Synthetic", kind = SourceKind.XTREAM,
            url = "https://example.invalid", username = "test", password = "test"))
        db.epgFeeds().insert(EpgFeed(id = 1, name = "Synthetic provider", providerSourceId = 1,
            lastSyncMillis = now, lastAttemptMillis = now, failureCount = 1, lastResult = "Retain"))
        db.channels().upsertAll(listOf(Channel(id = 1, sourceId = 1, streamId = "1",
            name = "FOX East", groupKey = "foxeast", categoryId = null, logoUrl = null,
            epgChannelId = null, matchedEpgId = syntheticId, number = null,
            streamUrl = "https://example.invalid/live")))
        db.programmes().upsertAll(listOf(Programme(feedId = 1, epgChannelId = syntheticId,
            startUtcMillis = now, endUtcMillis = now + 3_600_000, title = "Cached schedule")))
        db.epgAliases().upsertAll(listOf(EpgChannelAlias(1, syntheticId, "FOX East", "foxeast")))
    }

    @After fun tearDown() = db.close()

    private suspend fun assertCached() {
        assertEquals("Cached schedule", db.programmes().upcoming(syntheticId, now, 100).single().title)
        assertEquals(1, db.epgAliases().count())
        assertEquals(syntheticId, db.channels().byId(1)!!.matchedEpgId)
        assertEquals("Retain", db.epgFeeds().forProvider(1)!!.lastResult)
    }

    @Test fun `below hard floor normal and forced sync preserve all cache without HTTP or writes`() = runBlocking {
        freeBlocks(262_144)
        val before = db.epgFeeds().all()
        for (force in listOf(false, true)) {
            val summary = repository.syncAll(now, force)
            assertEquals(0, summary.programmesWritten)
            assertEquals(1, summary.channelsMatched)
            assertEquals(1, summary.channelsTotal)
            assertCached()
        }
        assertEquals(0, requests)
        assertEquals(before, db.epgFeeds().all()) // No new built-ins or failure counters.
    }

    @Test fun `between soft and hard reserves defers forced refresh too`() = runBlocking {
        freeBlocks(310_000) // 1.18 GiB, below 1.25 GiB soft reserve.
        repository.syncAll(now, true)
        assertEquals(0, requests)
        assertCached()
    }

    @Test fun `space falling below hard floor during short EPG request does not throw or replace cache`() = runBlocking {
        dropDuringShortRequest = true
        val summary = repository.syncAll(now)
        assertEquals(1, shortRequests)
        assertEquals(0, summary.programmesWritten)
        assertCached()
    }

    @Test fun `adequate storage still populates short EPG and alias`() = runBlocking {
        val summary = repository.syncAll(now)
        assertEquals(1, shortRequests)
        assertEquals(1, summary.programmesWritten)
        assertEquals("New schedule", db.programmes().upcoming(syntheticId, now, 100).single().title)
        assertEquals(1, db.epgAliases().count())
    }

    @Test fun `fallback cancellation propagates without altering cached rows`() = runBlocking {
        cancelDuringShortRequest = true
        try {
            repository.syncAll(now)
            fail("cancellation swallowed")
        } catch (_: CancellationException) {
            assertEquals(1, shortRequests)
            assertCached()
        }
    }

    @Test fun `feed cooldown does not turn a known failed guide healthy`() = runBlocking {
        // The fixture's persisted provider failure is inside its retry cooldown. The bounded
        // short-guide endpoint can still provide a current listing, but no bulk XMLTV retry ran.
        val summary = repository.syncAll(now)
        assertEquals(0, summary.feedsFailed)
        assertTrue(repository.health.value.failed)
        assertFalse(repository.health.value.refreshing)
        assertEquals(requests, shortRequests)
        assertEquals(1, db.epgFeeds().forProvider(1)!!.failureCount)
    }

    @Test fun `cancelled refresh retains the previous failed notice and clears loading`() = runBlocking {
        repository.syncAll(now)
        assertTrue(repository.health.value.failed)
        cancelDuringShortRequest = true
        try {
            repository.syncAll(now + 1, force = true)
            fail("Cancellation swallowed")
        } catch (_: CancellationException) {
            assertTrue(repository.health.value.failed)
            assertFalse(repository.health.value.refreshing)
            assertFalse(repository.health.value.storageBlocked)
            assertEquals("New schedule", db.programmes().upcoming(syntheticId, now, 100).single().title)
        }
    }
}
