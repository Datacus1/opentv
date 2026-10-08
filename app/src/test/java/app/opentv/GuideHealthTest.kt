/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.GuideHealth
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuideHealthTest {
    @Test fun `healthy guide preserves the caller's localized empty label`() {
        assertThat(GuideHealth().notice()).isNull()
        assertThat(GuideHealth().emptyListingMessage("No listing supplied")).isEqualTo("No listing supplied")
    }

    @Test fun `storage reason takes precedence over loading and feed failure`() {
        val health = GuideHealth(storageBlocked = true, refreshing = true, failed = true)
        assertThat(health.notice()).contains("low storage")
        assertThat(health.emptyListingMessage("No guide information")).contains("low storage")
        assertThat(health.emptyListingMessage("No guide information")).doesNotContain("No guide information")
    }

    @Test fun `loading does not claim that the provider has no schedule`() {
        assertThat(GuideHealth(refreshing = true).notice()).isEqualTo("Updating guide…")
        assertThat(GuideHealth(refreshing = true).emptyListingMessage("No guide information"))
            .isEqualTo("Guide updating…")
    }

    @Test fun `failed update is distinct from an unavailable provider schedule`() {
        val health = GuideHealth(failed = true)
        assertThat(health.notice()).contains("cached listings retained")
        assertThat(health.emptyListingMessage("No guide information")).contains("refresh failed")
    }
}
