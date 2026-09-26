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
    private val now = 1_800_000_000_000L

    @Test
    fun `fresh cache covering the next worker window is reused`() {
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = now - TimeUnit.MINUTES.toMillis(90),
                cachedUntilMillis = now + TimeUnit.HOURS.toMillis(12),
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
                cachedUntilMillis = now + TimeUnit.HOURS.toMillis(2),
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
                cachedUntilMillis = now + TimeUnit.HOURS.toMillis(2),
                nowUtcMillis = now,
                force = false,
            ),
        ).isFalse()
    }

    @Test
    fun `never synced expired and forced feeds refresh`() {
        assertThat(EpgRepository.shouldRefreshFeed(0L, null, now, false)).isTrue()
        assertThat(
            EpgRepository.shouldRefreshFeed(
                lastSyncMillis = now - TimeUnit.HOURS.toMillis(7),
                cachedUntilMillis = now + TimeUnit.DAYS.toMillis(2),
                nowUtcMillis = now,
                force = false,
            ),
        ).isTrue()
        assertThat(EpgRepository.shouldRefreshFeed(now, now + 1L, now, true)).isTrue()
    }
}
