/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv

import app.opentv.youtube.YoutubeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

/** Explicit PC-only opt-in. Ordinary/offline tests never contact YouTube. */
class YoutubeExtractionSmokeTest {
    @Test fun `public search resolves independently playable media`() = runBlocking {
        assumeTrue(System.getenv("OPENTV_YOUTUBE_SMOKE") == "1")
        try {
            val results = YoutubeRepository.search("Blender Big Buck Bunny")
            assertTrue(results.isNotEmpty())
            val media = YoutubeRepository.resolve(results.first())
            val http = OkHttpClient()
            for (url in listOfNotNull(media.videoUrl, media.audioUrl)) {
                http.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-1023").build())
                    .execute().use { response ->
                        assertTrue("Media HTTP status ${response.code}", response.isSuccessful)
                        assertTrue(response.body!!.source().readByteArray(1024).isNotEmpty())
                    }
            }
            println("YouTube smoke: search and bounded media-byte probe passed")
        } catch (failure: Exception) {
            // Never expose signed playback URLs or response bodies in the test report.
            fail("YouTube smoke failed: ${failure.javaClass.simpleName}")
        }
    }
}
