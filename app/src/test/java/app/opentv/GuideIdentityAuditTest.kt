package app.opentv

import app.opentv.data.model.*
import app.opentv.data.db.GuideCoverageRow
import app.opentv.data.repo.GuideIdentityAudit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GuideIdentityAuditTest {
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
