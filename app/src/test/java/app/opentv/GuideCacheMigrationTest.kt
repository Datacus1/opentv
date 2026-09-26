/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideCacheMigrationTest {
    @Test
    fun `v11 guide rows survive natural-key migration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE epg_feeds (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    name TEXT NOT NULL, url TEXT, providerSourceId INTEGER,
                                    builtIn INTEGER NOT NULL, enabled INTEGER NOT NULL,
                                    lastSyncMillis INTEGER NOT NULL, lastResult TEXT NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE programmes (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    feedId INTEGER NOT NULL, epgChannelId TEXT NOT NULL,
                                    startUtcMillis INTEGER NOT NULL, endUtcMillis INTEGER NOT NULL,
                                    title TEXT NOT NULL, description TEXT, category TEXT,
                                    season INTEGER, episode INTEGER, iconUrl TEXT
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE UNIQUE INDEX index_programmes_feedId_epgChannelId_startUtcMillis " +
                                    "ON programmes(feedId, epgChannelId, startUtcMillis)",
                            )
                            db.execSQL(
                                "CREATE INDEX index_programmes_endUtcMillis ON programmes(endUtcMillis)",
                            )
                            db.execSQL(
                                "CREATE INDEX index_programmes_epgChannelId ON programmes(epgChannelId)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL(
            """
            INSERT INTO epg_feeds
                (id, name, url, providerSourceId, builtIn, enabled, lastSyncMillis, lastResult)
            VALUES (7, 'Guide', NULL, NULL, 0, 1, 123, 'working')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO programmes
                (id, feedId, epgChannelId, startUtcMillis, endUtcMillis, title,
                 description, category, season, episode, iconUrl)
            VALUES (9, 7, 'channel', 100, 200, 'Old title', NULL, NULL, NULL, NULL, NULL)
            """.trimIndent(),
        )

        OpenTvDatabase.MIGRATION_11_12.migrate(db)

        db.query(
            "SELECT title, lastSeenSyncMillis FROM programmes " +
                "WHERE feedId = 7 AND epgChannelId = 'channel' AND startUtcMillis = 100",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Old title")
            assertThat(cursor.getLong(1)).isEqualTo(0L)
        }
        db.query(
            "SELECT lastAttemptMillis, failureCount, deleting FROM epg_feeds WHERE id = 7",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(0L)
            assertThat(cursor.getInt(1)).isEqualTo(0)
            assertThat(cursor.getInt(2)).isEqualTo(0)
        }
        db.query("PRAGMA table_info(programmes)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val pkColumn = cursor.getColumnIndexOrThrow("pk")
            val primaryKeyOrder = linkedMapOf<String, Int>()
            while (cursor.moveToNext()) {
                primaryKeyOrder[cursor.getString(nameColumn)] = cursor.getInt(pkColumn)
            }
            assertThat(primaryKeyOrder["feedId"]).isEqualTo(1)
            assertThat(primaryKeyOrder["epgChannelId"]).isEqualTo(2)
            assertThat(primaryKeyOrder["startUtcMillis"]).isEqualTo(3)
        }
        helper.close()
    }
}
