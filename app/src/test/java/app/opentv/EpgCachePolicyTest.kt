/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test

class EpgCachePolicyTest {

    @Test
    fun `new sports companion inherits an enabled US guide exactly once`() {
        assertThat(
            EpgRepository.defaultEnabledForNewBuiltIn(
                EpgRepository.USA_SPORTS_FEED_NAME,
                usaMainEnabled = true,
            ),
        ).isTrue()
        assertThat(
            EpgRepository.defaultEnabledForNewBuiltIn(
                EpgRepository.USA_SPORTS_FEED_NAME,
                usaMainEnabled = false,
            ),
        ).isFalse()
        assertThat(
            EpgRepository.defaultEnabledForNewBuiltIn(
                EpgRepository.USA_FEED_NAME,
                usaMainEnabled = true,
            ),
        ).isFalse()
    }

    private val now = 1_800_000_000_000L

    @Test
    fun `fresh cache covering the next worker window is reused`() {
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = now - TimeUnit.MINUTES.toMillis(90),
                lastAttemptMillis = now - TimeUnit.MINUTES.toMillis(90),
                failureCount = 0,
                populatedChannels = 100,
                coveredChannels = 90,
                nowUtcMillis = now,
                force = false,
            ),
        ).isFalse()
    }

    @Test
    fun `fresh timestamp does not hide a guide that is about to run dry`() {
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = now - TimeUnit.HOURS.toMillis(2),
                lastAttemptMillis = now - TimeUnit.HOURS.toMillis(2),
                failureCount = 0,
                populatedChannels = 100,
                coveredChannels = 20,
                nowUtcMillis = now,
                force = false,
            ),
        ).isTrue()
    }

    @Test
    fun `short guide is not downloaded repeatedly during cooldown`() {
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = now - TimeUnit.MINUTES.toMillis(20),
                lastAttemptMillis = now - TimeUnit.MINUTES.toMillis(20),
                failureCount = 0,
                populatedChannels = 100,
                coveredChannels = 20,
                nowUtcMillis = now,
                force = false,
            ),
        ).isFalse()
    }

    @Test
    fun `never synced expired and forced feeds refresh`() {
        assertThat(EpgRepository.shouldRefreshFeed(0L, 0L, 0, 0, 0, now, false)).isTrue()
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = now - TimeUnit.HOURS.toMillis(7),
                lastAttemptMillis = now - TimeUnit.HOURS.toMillis(7),
                failureCount = 0,
                populatedChannels = 100,
                coveredChannels = 100,
                nowUtcMillis = now,
                force = false,
            ),
        ).isTrue()
        assertThat(EpgRepository.shouldRefreshFeed(now, now, 0, 100, 100, now, true)).isTrue()
    }

    @Test
    fun `single far future outlier cannot hide broad coverage failure`() {
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = now - TimeUnit.HOURS.toMillis(2),
                lastAttemptMillis = now - TimeUnit.HOURS.toMillis(2),
                failureCount = 0,
                populatedChannels = 500,
                coveredChannels = 1,
                nowUtcMillis = now,
                force = false,
            ),
        ).isTrue()
    }

    @Test
    fun `failed feed observes exponential retry backoff`() {
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = 0L,
                lastAttemptMillis = now - TimeUnit.MINUTES.toMillis(30),
                failureCount = 2,
                populatedChannels = 0,
                coveredChannels = 0,
                nowUtcMillis = now,
                force = false,
            ),
        ).isFalse()
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = 0L,
                lastAttemptMillis = now - TimeUnit.HOURS.toMillis(2),
                failureCount = 2,
                populatedChannels = 0,
                coveredChannels = 0,
                nowUtcMillis = now,
                force = false,
            ),
        ).isTrue()
    }
}
