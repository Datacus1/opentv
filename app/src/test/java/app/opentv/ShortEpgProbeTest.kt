package app.opentv

import app.opentv.data.remote.*
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ShortEpgProbeTest {
    private val now = 1_780_000_500_000L
    private fun row(start: String = "1780000000", stop: String = "1780003600", title: String = "Private title") =
        """{"start_timestamp":"$start","stop_timestamp":"$stop","title":"$title","description":"Private description"}"""
    @Test fun `counts exact production title and duplicate rules without exporting contents`() {
        val result = analyseShortEpgProbe("""{"epg_listings":[${row()},${row()},${row(title = "")},${row(start = "oops")},${row(stop = "0")},null]}""", now)
        assertEquals(6, result.encountered); assertEquals(1, result.accepted)
        assertEquals(1, result.current); assertEquals(1, result.duplicateStarts)
        assertEquals(1, result.missingTitle); assertEquals(1, result.invalidTime)
        assertEquals(1, result.invalidSpan); assertEquals(1, result.invalidObject)
        assertFalse(Json.encodeToString(result).contains("Private"))
    }
    @Test fun `invalid shape and deep json never masquerade as an empty guide`() {
        assertEquals("INVALID_SHAPE", analyseShortEpgProbe("{}", now).status)
        assertEquals("INVALID_JSON", analyseShortEpgProbe("not json", now).status)
        assertEquals("DEPTH_LIMIT", analyseShortEpgProbe("[".repeat(65) + "]".repeat(65), now).status)
        assertEquals("COMPLETE", analyseShortEpgProbe("""{"epg_listings":[]}""", now).status)
    }
    @Test fun `overflow timestamps are rejected not counted as usable listings`() {
        val result = analyseShortEpgProbe("""{"epg_listings":[${row(stop = Long.MAX_VALUE.toString())}]}""", now)
        assertEquals(1, result.invalidSpan); assertEquals(0, result.accepted)
        val positiveWrap = analyseShortEpgProbe("""{"epg_listings":[${row(start = "18446745853709551", stop = "18446745853713151")}]}""", now)
        assertEquals(1, positiveWrap.invalidSpan); assertEquals(0, positiveWrap.accepted)
        assertEquals(0, positiveWrap.current)
    }
    @Test fun `accepted out of retention window is not a usable cached schedule`() {
        val result = analyseShortEpgProbe("""{"epg_listings":[${row(start = "1700000000", stop = "1700003600")},${row(start = "1900000000", stop = "1900003600")}]}""", now)
        assertEquals(2, result.accepted); assertEquals(2, result.outOfWindow); assertEquals(0, result.usableInWindow)
    }
    @Test fun `bounded endpoint preserves internal credentials and closes normal response`() = runBlocking {
        val closed = AtomicBoolean()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("20", request.url.queryParameter("limit"))
            assertEquals("secret-user", request.url.queryParameter("username"))
            assertEquals("internal-stream", request.url.queryParameter("stream_id"))
            val delegate = """{"epg_listings":[${row()}]}""".toResponseBody()
            val body = object : ResponseBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun source() = delegate.source()
                override fun close() { closed.set(true); delegate.close() }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val source = Source(id = 1, name = "test", kind = SourceKind.XTREAM,
            url = "https://example.invalid", username = "secret-user", password = "secret-password")
        val result = XtreamApi(client).probeShortEpg(source, "internal-stream", now)
        assertEquals("COMPLETE", result.status); assertEquals(1, result.accepted); assertTrue(closed.get())
        assertFalse(Json.encodeToString(result).contains("secret"))
    }
    @Test fun `oversized decoded body is partial even when server ignores requested limit`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(" ".repeat(SHORT_PROBE_MAX_BYTES.toInt() + 1).toResponseBody()).build()
        }.build()
        val result = XtreamApi(client).probeShortEpg(Source(id = 1, name = "test", kind = SourceKind.XTREAM,
            url = "https://example.invalid", username = "u", password = "p"), "1", now)
        assertEquals("BYTE_LIMIT", result.status); assertEquals(0, result.accepted)
    }
    @Test fun `coroutine deadline cancels owned call and closes in flight response`() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val source = object : okio.Source {
                override fun timeout() = okio.Timeout.NONE
                override fun close() { closed.countDown() }
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    entered.countDown()
                    while (!chain.call().isCanceled()) Thread.sleep(5)
                    throw java.io.IOException("Synthetic cancellation")
                }
            }
            val body = object : ResponseBody() {
                private val buffered = source.buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source() = buffered
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        try {
            kotlinx.coroutines.withTimeout(500) {
                XtreamApi(client).probeShortEpg(Source(id = 1, name = "test", kind = SourceKind.XTREAM,
                    url = "https://example.invalid", username = "u", password = "p"), "1", now)
            }
            fail("deadline did not cancel")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }
}
