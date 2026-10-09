/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.GuideStorageRecovery
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Injected cache/storage operations: no provider, real cache clear or device allocation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideStorageRecoveryTest {
    @Test fun `platform request permits safe partial recovery without exceeding allowance`() {
        assertEquals(0L, GuideStorageRecovery.platformReclaimRequest(100L, 0L))
        assertEquals(0L, GuideStorageRecovery.platformReclaimRequest(100L, -1L))
        assertEquals(40L, GuideStorageRecovery.platformReclaimRequest(100L, 40L))
        assertEquals(100L, GuideStorageRecovery.platformReclaimRequest(100L, 100L))
        assertEquals(100L, GuideStorageRecovery.platformReclaimRequest(100L, 200L))
    }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var bytes = EpgRepository.HARD_FREE_FLOOR_BYTES - 1
    private var idle = true
    private var clock = 1_800_000_000_000L
    private var stored = 0L
    private var trims = 0
    private var requests = 0
    private var requestedTarget = 0L
    private var trim: suspend () -> Unit = {}
    private var platform: suspend () -> Unit = {}

    private fun recovery() = GuideStorageRecovery(context, maintenanceAllowed = { idle },
        availableBytes = { bytes }, trimOwnCache = { trims++; trim() },
        requestPlatformSpace = { requestedTarget = it; requests++; platform() },
        nowMillis = { clock }, readLastAttempt = { stored }, writeLastAttempt = { stored = it })

    @Test fun `sufficient physical space skips all cache operations`() = runBlocking {
        bytes = EpgRepository.SOFT_FREE_RESERVE_BYTES
        assertTrue(recovery().recover())
        assertEquals(0, trims)
        assertEquals(0, requests)
        assertEquals(0L, stored)
    }

    @Test fun `active playback or recording leaves caches untouched`() = runBlocking {
        idle = false
        assertFalse(recovery().recover())
        assertEquals(0, trims)
        assertEquals(0, requests)
        assertEquals(0L, stored)
    }

    @Test fun `own disposable cache can recover reserve without platform request`() = runBlocking {
        trim = { bytes = EpgRepository.SOFT_FREE_RESERVE_BYTES }
        assertTrue(recovery().recover())
        assertEquals(1, trims)
        assertEquals(0, requests)
    }

    @Test fun `platform allocation uses total reserve and rechecks actual space`() = runBlocking {
        platform = { bytes = EpgRepository.SOFT_FREE_RESERVE_BYTES }
        assertTrue(recovery().recover())
        assertEquals(EpgRepository.SOFT_FREE_RESERVE_BYTES, requestedTarget)
        assertEquals(1, requests)
    }

    @Test fun `successful platform call without actual free space is not recovery`() = runBlocking {
        assertFalse(recovery().recover())
        assertEquals(1, requests)
    }

    @Test fun `unsupported allocation and cache failures are contained`() = runBlocking {
        trim = { throw IllegalStateException("synthetic private detail") }
        platform = { throw IOException("synthetic private detail") }
        assertFalse(recovery().recover())
        assertEquals(1, trims)
        assertEquals(1, requests)
    }

    @Test fun `persistent cooldown survives a new recovery instance`() = runBlocking {
        assertFalse(recovery().recover())
        clock += GuideStorageRecovery.RETRY_INTERVAL_MILLIS - 1
        assertFalse(recovery().recover())
        assertEquals(1, trims)
        assertEquals(1, requests)
        clock++
        assertFalse(recovery().recover())
        assertEquals(2, trims)
        assertEquals(2, requests)
    }

    @Test fun `free space recovered externally bypasses failed attempt cooldown`() = runBlocking {
        val helper = recovery()
        assertFalse(helper.recover())
        bytes = EpgRepository.SOFT_FREE_RESERVE_BYTES
        assertTrue(helper.recover())
        assertEquals(1, trims)
    }

    @Test fun `backwards clock permits a single new attempt`() = runBlocking {
        val helper = recovery()
        assertFalse(helper.recover())
        clock -= 100_000
        assertFalse(helper.recover())
        assertFalse(helper.recover())
        assertEquals(2, trims)
    }

    @Test fun `playback beginning after cache trim stops platform request`() = runBlocking {
        trim = { idle = false }
        assertFalse(recovery().recover())
        assertEquals(1, trims)
        assertEquals(0, requests)
    }

    @Test fun `cancellation propagates from cache clear`() = runBlocking {
        trim = { throw CancellationException("cancel") }
        try {
            recovery().recover()
            fail("Cancellation swallowed")
        } catch (_: CancellationException) {
            assertEquals(0, requests)
        }
    }

    @Test fun `cancellation propagates from platform request`() = runBlocking {
        platform = { throw CancellationException("cancel") }
        try {
            recovery().recover()
            fail("Cancellation swallowed")
        } catch (_: CancellationException) {
            assertEquals(1, requests)
        }
    }

    @Test fun `unreadable physical free space fails closed`() = runBlocking {
        val helper = GuideStorageRecovery(context, { true },
            availableBytes = { throw IOException("unavailable") },
            trimOwnCache = {}, requestPlatformSpace = {}, nowMillis = { clock },
            readLastAttempt = { 0L }, writeLastAttempt = {})
        assertFalse(helper.recover())
    }

    @Test fun `failed idle check does not clear caches`() = runBlocking {
        val helper = GuideStorageRecovery(context, { throw IOException("unavailable") },
            availableBytes = { bytes }, trimOwnCache = { trims++ },
            requestPlatformSpace = { requests++ }, nowMillis = { clock },
            readLastAttempt = { 0L }, writeLastAttempt = {})
        assertFalse(helper.recover())
        assertEquals(0, trims)
        assertEquals(0, requests)
    }

    @Test fun `timestamp write failure still throttles within process`() = runBlocking {
        val helper = GuideStorageRecovery(context, { true }, availableBytes = { bytes },
            trimOwnCache = { trims++ }, requestPlatformSpace = { requests++ },
            nowMillis = { clock }, readLastAttempt = { 0L },
            writeLastAttempt = { throw IOException("unavailable") })
        assertFalse(helper.recover())
        assertFalse(helper.recover())
        assertEquals(1, trims)
        assertEquals(1, requests)
    }

    @Test fun `future saved timestamp with failed writes does not bypass process cooldown`() = runBlocking {
        val futureSaved = clock + GuideStorageRecovery.RETRY_INTERVAL_MILLIS
        val helper = GuideStorageRecovery(context, { true }, availableBytes = { bytes },
            trimOwnCache = { trims++ }, requestPlatformSpace = { requests++ },
            nowMillis = { clock }, readLastAttempt = { futureSaved },
            writeLastAttempt = { throw IOException("unavailable") })
        assertFalse(helper.recover()) // One normalized attempt after a backwards clock correction.
        repeat(3) { assertFalse(helper.recover()) }
        assertEquals(1, trims)
        assertEquals(1, requests)
        clock += GuideStorageRecovery.RETRY_INTERVAL_MILLIS
        assertFalse(helper.recover())
        assertEquals(2, trims)
        assertEquals(2, requests)
    }
}
