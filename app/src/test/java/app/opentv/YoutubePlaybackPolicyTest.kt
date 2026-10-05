/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv

import app.opentv.youtube.YoutubePlaybackPolicy
import org.junit.Assert.*
import org.junit.Test

class YoutubePlaybackPolicyTest {
    @Test fun `hardware friendly AVC and VP9 at 1080p or below are accepted`() {
        assertTrue(YoutubePlaybackPolicy.supports("avc1.640028", "1080p60", 60))
        assertTrue(YoutubePlaybackPolicy.supports("vp09.00.41.08", "720p", 30))
        assertTrue(YoutubePlaybackPolicy.supports("vp9", "480p", 0))
    }
    @Test fun `AV1 unknown codecs high resolution and high frame rates are rejected`() {
        assertFalse(YoutubePlaybackPolicy.supports("av01.0.08M.08", "1080p", 30))
        assertFalse(YoutubePlaybackPolicy.supports(null, "720p", 30))
        assertFalse(YoutubePlaybackPolicy.supports("avc1", "2160p", 30))
        assertFalse(YoutubePlaybackPolicy.supports("avc1", "1080p120", 120))
        assertFalse(YoutubePlaybackPolicy.supports("avc1", "unknown", 30))
    }
}
