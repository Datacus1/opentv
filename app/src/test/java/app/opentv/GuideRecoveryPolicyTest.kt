/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import app.opentv.data.work.GuideMaintenanceWorker
import app.opentv.data.work.GuideRecoveryWorker
import app.opentv.data.work.GuideCatchUpWorker
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideRecoveryPolicyTest {
    @Test fun `catch up persists without network or low storage constraint and uses bounded linear retry`() {
        val spec = GuideCatchUpWorker.request().workSpec
        assertThat(spec.initialDelay).isEqualTo(30_000)
        assertThat(spec.backoffPolicy).isEqualTo(BackoffPolicy.LINEAR)
        assertThat(spec.backoffDelayDuration).isEqualTo(30_000)
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(spec.constraints.requiresStorageNotLow()).isFalse()
        assertThat(GuideCatchUpWorker.WORK_NAME).isNotEqualTo(GuideMaintenanceWorker.WORK_NAME)
    }
    @Test fun `storage deferral retries even when no feed failed`() {
        assertThat(GuideRecoveryWorker.needsRetry(true, 0)).isTrue()
        assertThat(GuideRecoveryWorker.needsRetry(false, 1)).isTrue()
        assertThat(GuideRecoveryWorker.needsRetry(false, 0)).isFalse()
    }

    @Test fun `recovery has bounded delay exponential backoff and safe constraints`() {
        val spec = GuideRecoveryWorker.request().workSpec
        assertThat(spec.initialDelay).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(spec.backoffDelayDuration).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(spec.backoffPolicy).isEqualTo(BackoffPolicy.EXPONENTIAL)
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(spec.constraints.requiresStorageNotLow()).isTrue()
        assertThat(spec.workerClassName).isEqualTo(GuideRecoveryWorker::class.java.name)
    }

    @Test fun `local maintenance remains eligible offline and at low storage`() {
        val spec = GuideMaintenanceWorker.request().workSpec
        assertThat(spec.intervalDuration).isEqualTo(TimeUnit.MINUTES.toMillis(30))
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(spec.constraints.requiresStorageNotLow()).isFalse()
        assertThat(spec.workerClassName).isEqualTo(GuideMaintenanceWorker::class.java.name)
    }

    @Test fun `restored space wakes guide recovery without the old retry delay`() {
        val spec = GuideRecoveryWorker.request(restoredSpace = true).workSpec
        assertThat(spec.initialDelay).isEqualTo(0)
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(spec.constraints.requiresStorageNotLow()).isTrue()
        assertThat(spec.workerClassName).isEqualTo(GuideRecoveryWorker::class.java.name)
        assertThat(GuideRecoveryWorker.RESTORED_SPACE_WORK_NAME)
            .isNotEqualTo(GuideRecoveryWorker.WORK_NAME)
        assertThat(GuideRecoveryWorker.COALESCING_POLICY).isEqualTo(ExistingWorkPolicy.KEEP)
    }
}
