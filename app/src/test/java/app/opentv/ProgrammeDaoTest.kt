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
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProgrammeDaoTest {
    private lateinit var database: OpenTvDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `natural key upsert applies schedule correction instead of duplicating`() = runBlocking {
        val feedId = database.epgFeeds().insert(EpgFeed(name = "Test", enabled = true))
        val start = 1_800_000_000_000L
        database.programmes().upsertAll(
            listOf(
                Programme(
                    feedId = feedId,
                    epgChannelId = "channel",
                    startUtcMillis = start,
                    endUtcMillis = start + 30_000,
                    title = "Old title",
                    lastSeenSyncMillis = 1,
                ),
            ),
        )
        database.programmes().upsertAll(
            listOf(
                Programme(
                    feedId = feedId,
                    epgChannelId = "channel",
                    startUtcMillis = start,
                    endUtcMillis = start + 60_000,
                    title = "Corrected title",
                    lastSeenSyncMillis = 2,
                ),
            ),
        )

        val rows = database.programmes().observeWindow(start - 1, start + 120_000).first()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().title).isEqualTo("Corrected title")
        assertThat(rows.single().endUtcMillis).isEqualTo(start + 60_000)
        assertThat(rows.single().lastSeenSyncMillis).isEqualTo(2)
    }

    @Test
    fun `retention deletion is bounded per transaction`() = runBlocking {
        val feedId = database.epgFeeds().insert(EpgFeed(name = "Test", enabled = true))
        val rows = (0 until 2_500).map { index ->
            Programme(
                feedId = feedId,
                epgChannelId = "channel-$index",
                startUtcMillis = index.toLong(),
                endUtcMillis = index.toLong() + 1,
                title = "Programme $index",
            )
        }
        rows.chunked(500).forEach { database.programmes().upsertAll(it) }

        assertThat(database.programmes().deleteExpiredBatch(10_000, 1_000)).isEqualTo(1_000)
        assertThat(database.programmes().countForFeed(feedId)).isEqualTo(1_500)
        assertThat(database.programmes().deleteExpiredBatch(10_000, 1_000)).isEqualTo(1_000)
        assertThat(database.programmes().countForFeed(feedId)).isEqualTo(500)
    }

    @Test
    fun `overflow cleanup discards farthest future rows first`() = runBlocking {
        val feedId = database.epgFeeds().insert(EpgFeed(name = "Test", enabled = true))
        database.programmes().upsertAll(
            (1L..5L).map { start ->
                Programme(
                    feedId = feedId,
                    epgChannelId = "channel-$start",
                    startUtcMillis = start,
                    endUtcMillis = start + 1,
                    title = "Programme $start",
                )
            },
        )

        assertThat(database.programmes().deleteFarthestFutureBatchForFeed(feedId, 2)).isEqualTo(2)
        val remaining = database.programmes().observeWindow(0, 10).first()
        assertThat(remaining.map { it.startUtcMillis }).containsExactly(1L, 2L, 3L)
        Unit
    }

    @Test
    fun `disabled feed cache is retained but excluded from guide reads`() = runBlocking {
        val feedId = database.epgFeeds().insert(EpgFeed(name = "Test", enabled = true))
        database.programmes().upsertAll(
            listOf(
                Programme(
                    feedId = feedId,
                    epgChannelId = "channel",
                    startUtcMillis = 10,
                    endUtcMillis = 20,
                    title = "Programme",
                ),
            ),
        )
        database.epgFeeds().setEnabled(feedId, false)

        assertThat(database.programmes().countForFeed(feedId)).isEqualTo(1)
        assertThat(database.programmes().observeWindow(0, 30).first()).isEmpty()
    }
}
