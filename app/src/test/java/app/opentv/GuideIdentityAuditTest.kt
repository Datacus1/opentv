package app.opentv

import app.opentv.data.model.*
import app.opentv.data.db.GuideCoverageRow
import app.opentv.data.repo.GuideIdentityAudit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GuideIdentityAuditTest {
    @Test fun `coverage-only identities are retained with feed and exact identity provenance`() {
        val feeds = listOf(EpgFeed(id = 6, name = "Private", providerSourceId = 1),
            EpgFeed(id = 7, name = "Disabled", enabled = false))
        val aliases = listOf(EpgChannelAlias(6, "CBSWIAT.us", "WIAT", "unused"))
        val rows = listOf(GuideCoverageRow(6, "cbswiat.us", 3, 1, 2, 100, 50),
            GuideCoverageRow(6, "CBSWIAT.us ", 2, 1, 1, 100, 50),
            GuideCoverageRow(7, "CBSWIAT.us", 4, 1, 3, 100, 50))
        val snapshot = GuideIdentityAudit.snapshot(emptyList(), feeds, aliases, rows)
        val page = snapshot.page("coverage", 0, 100)
        assertEquals(3, page.coverage.size)
        assertTrue(page.coverage.none { it.hasAlias })
        assertEquals(3, page.coverage.map { it.fingerprint }.distinct().size)
        assertEquals(0, snapshot.aliases.single().totalRows)
        assertTrue(page.coverageComplete)
        assertEquals(3, page.coverageIdentitiesTotal)
        assertFalse(page.coverage.single { it.feedId == 7L }.enabled)
    }
    @Test fun `coverage export cap and lossy labels never masquerade as complete identities`() {
        val rows = (0..GuideIdentityAudit.MAX_COVERAGE_IDENTITIES).map {
            GuideCoverageRow(6, if (it == 0) "https://private/user/password" else "a".repeat(160) + it,
                1, 1, 0, 100, null)
        }
        val snapshot = GuideIdentityAudit.snapshot(emptyList(), emptyList(), emptyList(), rows)
        assertFalse(snapshot.coverageComplete)
        assertEquals(rows.size, snapshot.coverageIdentitiesTotal)
        assertEquals(GuideIdentityAudit.MAX_COVERAGE_IDENTITIES, snapshot.coverage.size)
        assertEquals(snapshot.coverage.size, snapshot.coverage.map { it.fingerprint }.distinct().size)
        assertTrue(snapshot.coverage.all { it.lossyLabel })
        val json = Json.encodeToString(snapshot.page("coverage", 0, 100))
        assertFalse(json.contains("private"))
        assertFalse(json.contains("password"))
        val uri = GuideIdentityAudit.snapshot(emptyList(), emptyList(), emptyList(), listOf(rows.first()))
        val uriJson = Json.encodeToString(uri.page("coverage", 0, 100))
        assertTrue(uri.coverage.single().lossyLabel)
        assertFalse(uriJson.contains("private"))
        assertFalse(uriJson.contains("password"))
    }
    @Test fun `identity evidence excludes credentials and bounds pages`() {
        val channel = Channel(id = 1, sourceId = 7, streamId = "secret-stream-id",
            name = "[CA] CNN https://private/user/password", categoryId = "secret-category",
            logoUrl = "https://private/logo", epgChannelId = "https://private/epg",
            matchedEpgId = "cnn.ca", number = null, streamUrl = "https://private/stream")
        val feed = EpgFeed(id = 3, name = "Secret feed", url = "https://private/feed", providerSourceId = 7)
        val alias = EpgChannelAlias(3, "cnn.ca", "CNN", "cnn")
        val snapshot = GuideIdentityAudit.snapshot((1L..250).map { channel.copy(id = it) },
            listOf(feed), listOf(alias), listOf(GuideCoverageRow(3, "cnn.ca", 3, 1, 2, 50, 30, 3)))
        val first = snapshot.page("channels", -1, 1000)
        assertEquals(250, first.total)
        assertEquals(100, first.channels.size)
        assertEquals("CA", first.channels.first().region)
        val encoded = Json.encodeToString(first)
        listOf("https://", "private", "user/password", "secret-stream", "secret-category", "Secret feed")
            .forEach { assertFalse(it, encoded.contains(it)) }
        val aliases = snapshot.page("aliases", 0, 100).aliases
        assertEquals(1, aliases.size)
        assertEquals(1, aliases.single().currentRows)
        assertTrue(aliases.single().enabled)
        assertEquals(7L, aliases.single().providerSourceId)
        assertTrue(snapshot.page("channels", 500, 10).channels.isEmpty())
    }

    @Test fun `encoded URI aliases and IDs are redacted and unavailable feeds remain explicit`() {
        val feeds = listOf(EpgFeed(id = 1, name = "Never exported", deleting = true))
        val aliases = listOf(EpgChannelAlias(1, "https%3a%2f%2fprivate/credentials", "https://private/name", "unused"),
            EpgChannelAlias(2, "missing", "Missing", "missing"))
        val snapshot = GuideIdentityAudit.snapshot(emptyList(), feeds, aliases, emptyList())
        assertTrue(snapshot.aliases.none { it.enabled })
        val encoded = Json.encodeToString(snapshot.page("aliases", 0, 100))
        assertFalse(encoded.contains("private"))
        assertFalse(encoded.contains("credentials"))
        assertFalse(encoded.contains("Never exported"))
        try { snapshot.page("invalid", 0, 10); fail("Invalid kind accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
