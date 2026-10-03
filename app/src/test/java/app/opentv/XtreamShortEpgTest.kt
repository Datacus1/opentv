package app.opentv

import app.opentv.data.remote.parseShortEpg
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamShortEpgTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses string timestamps and base64 text`() {
        val rows = parseShortEpg(
            json.parseToJsonElement(
                """
                {
                  "epg_listings": [{
                    "title": "R29vZCBNb3JuaW5nIEFtZXJpY2E=",
                    "description": "TW9ybmluZyBuZXdz",
                    "start_timestamp": "1780000000",
                    "stop_timestamp": "1780003600"
                  }]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1, rows.size)
        assertEquals("Good Morning America", rows.single().title)
        assertEquals("Morning news", rows.single().description)
        assertEquals(1_780_000_000_000L, rows.single().startUtcMillis)
        assertEquals(1_780_003_600_000L, rows.single().endUtcMillis)
    }

    @Test
    fun `keeps plain text and drops malformed rows`() {
        val rows = parseShortEpg(
            json.parseToJsonElement(
                """
                {
                  "epg_listings": [
                    {
                      "title": "FOX 5 News",
                      "description": "",
                      "start_timestamp": 1780000000,
                      "stop_timestamp": 1780001800
                    },
                    {
                      "title": "Bad duration",
                      "start_timestamp": 1780001800,
                      "stop_timestamp": 1780001700
                    },
                    {
                      "title": "Missing stop",
                      "start_timestamp": 1780001800
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1, rows.size)
        assertEquals("FOX 5 News", rows.single().title)
        assertNull(rows.single().description)
    }
}
