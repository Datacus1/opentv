/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StartupGuideWorkTest {
    @Test fun `optional startup failure is reported without escaping`() = runBlocking {
        val failure = IllegalStateException("storage reserve")
        var reported: Exception? = null
        runOptionalStartupGuideWork({ reported = it }) { throw failure }
        assertSame(failure, reported)
    }

    @Test fun `successful work runs once without failure reporting`() = runBlocking {
        var runs = 0
        runOptionalStartupGuideWork({ fail("unexpected failure") }) { runs++ }
        assertEquals(1, runs)
    }

    @Test fun `cancellation is not swallowed or reported as a refresh failure`() = runBlocking {
        val cancellation = CancellationException("cancel startup")
        try {
            runOptionalStartupGuideWork({ fail("cancellation reported as failure") }) {
                throw cancellation
            }
            fail("cancellation swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
