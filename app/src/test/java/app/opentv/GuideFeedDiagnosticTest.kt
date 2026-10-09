package app.opentv

import app.opentv.data.parser.XmltvParser
import app.opentv.data.repo.GuideFeedDiagnostic as D
import app.opentv.data.repo.EpgRepository
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideFeedDiagnosticTest {
    private val now = XmltvParser.parseXmltvTime("20261009180000 +0000")!!
    private fun programme(id: String, start: String = "20261009173000 +0000",
        stop: String? = "20261009183000 +0000") =
        "<programme channel=\"$id\" start=\"$start\" ${stop?.let { "stop=\"$it\"" }.orEmpty()}><title>Private title</title><desc>Private description</desc></programme>"
    private suspend fun scan(body: String, limits: D.Limits = D.Limits(), ids: List<String> = emptyList()) =
        D.scan("<tv>$body</tv>".byteInputStream(), 6, now, limits, ids)

    @Test fun `network record guard stops giant materialized nodes and preserves small production rows`() = runTest {
        val large = "<programme channel=\"a\" start=\"20261009173000 +0000\"><desc>" + "x".repeat(300_000) + "</desc></programme>"
        val partial = scan(large, D.Limits(recordBytes = 256 * 1024L))
        assertEquals(D.Completion.RECORD_LIMIT, partial.completion)
        assertTrue(partial.bytesRead <= 256 * 1024L)
        assertEquals(0, partial.encountered)
        val small = scan(programme("a") + programme("b"), D.Limits(recordBytes = 256 * 1024L))
        assertEquals(D.Completion.COMPLETE, small.completion)
        assertEquals(2, small.encountered)
        assertEquals(64, small.streamFingerprint.length)
    }
    @Test fun `diagnostic custom entity cannot expand a small input into an unbounded body`() = runTest {
        val xml = """<!DOCTYPE tv [<!ENTITY a "Private">]><tv><programme channel="a" start="20261009173000 +0000"><desc>&a;</desc></programme></tv>"""
        assertEquals(D.Completion.PARSE_ERROR, D.scan(xml.byteInputStream(), 6, now, D.Limits(recordBytes = 1024)).completion)
    }
    @Test fun `record bound also covers header alias unknown elements and gzip inflation`() = runTest {
        val huge = "x".repeat(300_000)
        for (body in listOf("<unknown>$huge</unknown>", "<channel id=\"a\"><display-name>$huge</display-name></channel>", huge)) {
            assertEquals(D.Completion.RECORD_LIMIT, scan(body, D.Limits(recordBytes = 256 * 1024L)).completion)
        }
        val compressed = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(compressed).use { it.write("<tv><unknown>$huge</unknown></tv>".toByteArray()) }
        val transport = app.opentv.data.repo.GuideFeedTransportLimit(compressed.toByteArray().inputStream())
        val inflated = D.scan(java.util.zip.GZIPInputStream(transport), 6, now, D.Limits(recordBytes = 256 * 1024L))
        assertEquals(D.Completion.RECORD_LIMIT, inflated.completion)
        assertFalse(transport.limitReached)
        val exact = app.opentv.data.repo.GuideFeedTransportLimit("abc".byteInputStream(), 3)
        assertEquals("abc", exact.readBytes().toString(Charsets.UTF_8)); assertFalse(exact.limitReached)
        val limited = app.opentv.data.repo.GuideFeedTransportLimit("abcd".byteInputStream(), 3)
        try { limited.readBytes(); fail("transport cap ignored") } catch (_: java.io.IOException) { }
        assertTrue(limited.limitReached); assertEquals(3L, limited.bytes)
    }

    @Test fun `exact raw IDs preserve case and spacing without inventing missing listings`() = runTest {
        val r = scan("<channel id=\"CBSWIAT.us\"/>" + programme("cbswiat.us") + programme("CBSWIAT.us "),
            ids = listOf("CBSWIAT.us", "CWWTTO.us"))
        assertEquals(D.Completion.COMPLETE, r.completion)
        assertEquals(4, r.rows.size)
        assertEquals(4, r.rows.map { it.fingerprint }.distinct().size)
        assertEquals(0, r.rows.single { it.guideId == "CBSWIAT.us" }.encountered)
        assertEquals(0, r.rows.single { it.guideId == "CWWTTO.us" }.encountered)
        assertEquals(2, r.rows.sumOf { it.accepted })
        assertEquals(2, r.rows.sumOf { it.selectedProductionKeys })
    }
    @Test fun `rejection and defaulted stop counters retain production acceptance semantics`() = runTest {
        val body = programme("ok") + programme("default", stop = null) + programme("badstop", stop = "oops") +
            programme("badstart", start = "bad") + programme("span", stop = "20261009170000 +0000") +
            "<programme channel=\"missing\"/><programme start=\"20261009173000 +0000\"/>"
        val r = scan(body)
        val accepted = mutableListOf<app.opentv.data.model.Programme>()
        val stats = XmltvParser.parse("<tv>$body</tv>".byteInputStream(), 6) { accepted += it }
        assertEquals(stats.programmeCount, r.rows.sumOf { it.accepted })
        assertEquals(stats.skippedProgrammes, r.rows.sumOf { it.blankChannel + it.invalidStart + it.missingStart + it.nonpositiveSpan })
        assertEquals(3, accepted.size)
        assertEquals(1, r.rows.single { it.guideId == "default" }.defaultedStop)
        assertEquals(1, r.rows.single { it.guideId == "badstop" }.defaultedStop)
        assertEquals(1, r.rows.single { it.guideId == "default" }.missingStop)
        assertEquals(1, r.rows.single { it.guideId == "badstop" }.invalidStop)
        assertEquals(1, r.rows.sumOf { it.blankChannel })
        assertEquals(1, r.rows.sumOf { it.missingStart })
    }
    @Test fun `window exclusion is distinct from missing and malformed programmes`() = runTest {
        val r = scan(programme("old", "20261001000000 +0000", "20261001010000 +0000") +
            programme("future", "20261020000000 +0000", "20261020010000 +0000") + programme("now"))
        assertEquals(3, r.rows.sumOf { it.accepted })
        assertEquals(2, r.rows.sumOf { it.outOfWindow })
        assertEquals(1, r.rows.sumOf { it.offeredToSelector })
        assertEquals(1, r.rows.sumOf { it.selectedProductionKeys })
    }
    @Test fun `late channel cap overflow remains visible even when XML parse completed`() = runTest {
        val r = scan(programme("early") + programme("late"), D.Limits(selectedRows = 2, selectorChannels = 1))
        assertEquals(D.Completion.COMPLETE, r.completion)
        assertTrue(r.selectorTruncated)
        assertEquals(1, r.rows.single { it.guideId == "late" }.offeredToSelector)
        assertEquals(0, r.rows.single { it.guideId == "late" }.selectedProductionKeys)
    }
    @Test fun `long IDs expose production truncation collisions without merging raw counters`() = runTest {
        val prefix = "a".repeat(EpgRepository.MAX_EPG_ID_CHARS)
        val r = scan(programme(prefix + "1") + programme(prefix + "2"))
        assertEquals(2, r.rows.size)
        assertTrue(r.rows.all { it.truncatedProductionId && it.productionIdentityCollision && it.lossyLabel })
        assertEquals(2, r.rows.map { it.fingerprint }.distinct().size)
        assertEquals(1, r.rows.map { it.productionFingerprint }.distinct().size)
    }
    @Test fun `limits and malformed input are explicitly incomplete`() = runTest {
        assertEquals(D.Completion.IDENTITY_LIMIT, scan(programme("a") + programme("b"), D.Limits(identities = 1)).completion)
        assertEquals(D.Completion.PROGRAMME_LIMIT, scan(programme("a") + programme("b"), D.Limits(programmes = 1)).completion)
        assertEquals(D.Completion.BYTE_LIMIT, scan(programme("a"), D.Limits(bytes = 10)).completion)
        val bad = D.scan("<tv><programme>".byteInputStream(), 6, now)
        assertEquals(D.Completion.PARSE_ERROR, bad.completion)
        var clock = 0L
        val timed = D.scan("<tv/>".byteInputStream(), 6, now, D.Limits(millis = 1),
            clockNanos = { clock.also { clock += 2_000_000 } })
        assertEquals(D.Completion.TIME_LIMIT, timed.completion)
    }
    @Test fun `exact byte budget EOF is complete and cancellation remains cancellation`() = runTest {
        val xml = "<tv/>".toByteArray()
        assertEquals(D.Completion.COMPLETE, D.scan(xml.inputStream(), 6, now, D.Limits(bytes = xml.size.toLong())).completion)
        var partial: D.Report? = null
        val input = object : InputStream() { override fun read(): Int = throw CancellationException("private") }
        try { D.scan(input, 6, now, onCancelled = { partial = it }); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(D.Completion.CANCELLED, partial?.completion)
    }
    @Test fun `diagnostic output has no programme bodies or URI credentials`() = runTest {
        val r = scan(programme("https://private/user/password"))
        val json = Json.encodeToString(r)
        listOf("https://", "private", "password", "Private title", "Private description").forEach {
            assertFalse(it, json.contains(it))
        }
        assertTrue(r.rows.single().lossyLabel)
    }
    @Test fun `strict diagnostic completeness does not tighten ordinary ingestion`() = runTest {
        val truncated = "<tv><programme channel=\"a\" start=\"20261009173000 +0000\"><title>Partial</title>"
        val ordinary = mutableListOf<app.opentv.data.model.Programme>()
        XmltvParser.parse(truncated.byteInputStream(), 6) { ordinary += it }
        assertEquals(1, ordinary.size) // Existing EOF handling, deliberately not fixed here.
        assertEquals(D.Completion.PARSE_ERROR, D.scan(truncated.byteInputStream(), 6, now).completion)
        val validButLenientDates = programme("lenient", "20260230173000 strange", "20260302183000 strange")
        val parsed = mutableListOf<app.opentv.data.model.Programme>()
        XmltvParser.parse("<tv>$validButLenientDates</tv>".byteInputStream(), 6) { parsed += it }
        val observed = scan(validButLenientDates)
        assertEquals(parsed.size, observed.rows.sumOf { it.accepted })
        assertEquals(1, parsed.size)
    }
    @Test fun `complete requires one fully closed XMLTV root not just EOF or any XML`() = runTest {
        for (xml in listOf("", " ", "<not-xmltv/>", "<programme/>", "<tv>", "<tv><channel id=\"a\">",
            "<tv/><tv/>", "<tv/>garbage", "<x:tv xmlns:x=\"urn:xmltv\"/>")) {
            assertEquals("Invalid document was called complete: $xml", D.Completion.PARSE_ERROR,
                D.scan(xml.byteInputStream(), 6, now).completion)
        }
        assertEquals(D.Completion.COMPLETE, D.scan("<?xml version=\"1.0\"?><tv/> ".byteInputStream(), 6, now).completion)
    }
    @Test fun `ordinary truncated channel preserves former EOF behavior without looping`() = runTest {
        val aliases = mutableListOf<String>()
        val xml = "<tv><channel id=\"a\"><display-name>Partial</display-name>"
        val stats = XmltvParser.parse(xml.byteInputStream(), 6,
            onChannelAlias = { id, _ -> aliases += id }) { fail("Unexpected programme") }
        assertEquals(1, stats.channelCount)
        assertEquals(listOf("a"), aliases)
        assertEquals(D.Completion.PARSE_ERROR, D.scan(xml.byteInputStream(), 6, now).completion)
    }
    @Test fun `offline supplied file opt in never fetches a provider`() = runTest {
        val input = System.getenv("OPENTV_DIAGNOSTIC_XML_FILE")
        assumeTrue("No supplied local XMLTV file", !input.isNullOrBlank())
        val anchor = System.getenv("OPENTV_DIAGNOSTIC_NOW_MILLIS")?.toLongOrNull()
        require(anchor != null) { "Supply explicit guide time anchor" }
        val output = System.getenv("OPENTV_DIAGNOSTIC_REPORT_FILE")
        require(!output.isNullOrBlank()) { "Supply output report file" }
        val feed = System.getenv("OPENTV_DIAGNOSTIC_FEED_ID")?.toLongOrNull() ?: 6
        val ids = System.getenv("OPENTV_DIAGNOSTIC_GUIDE_IDS").orEmpty().split(',').filter { it.isNotBlank() }
        val enabled = System.getenv("OPENTV_DIAGNOSTIC_ENABLED_FEEDS")?.toIntOrNull() ?: 3
        require(enabled in 1..20) { "Supply valid enabled feed count" }
        val limits = D.Limits(selectedRows = minOf(150_000, 200_000 / enabled))
        val r = File(input!!).inputStream().use { raw ->
            val stream = if (input.endsWith(".gz", true)) java.util.zip.GZIPInputStream(raw) else raw
            stream.use { D.scan(it, feed, anchor, limits, ids) }
        }
        java.nio.file.Files.write(File(output!!).toPath(), Json { prettyPrint = true }.encodeToString(r).toByteArray(Charsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
        println("Offline diagnostic: completion=${r.completion}; identities=${r.rows.size}; programmes=${r.encountered}")
    }
}
