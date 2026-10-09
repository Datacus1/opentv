package app.opentv.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val SHORT_PROBE_MAX_BYTES = 512L * 1024

/** No titles/descriptions/raw timestamps or transport data leave this diagnostic. */
@Serializable
internal data class ShortEpgProbeCounts(val status: String = "COMPLETE", val encountered: Int = 0,
    val invalidObject: Int = 0, val invalidTime: Int = 0, val invalidSpan: Int = 0,
    val missingTitle: Int = 0, val accepted: Int = 0, val duplicateStarts: Int = 0,
    val current: Int = 0, val future: Int = 0, val past: Int = 0,
    val outOfWindow: Int = 0, val usableInWindow: Int = 0)

internal fun analyseShortEpgProbe(text: String, now: Long): ShortEpgProbeCounts {
    // Bound nesting before the recursive JSON parser. Strings/escapes do not count as containers.
    var depth = 0; var quoted = false; var escaped = false
    for (c in text) {
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when (c) { '"' -> quoted = true; '{', '[' -> { if (++depth > 64) return ShortEpgProbeCounts("DEPTH_LIMIT") }; '}', ']' -> depth-- }
    }
    val root = try { Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(text) }
        catch (_: Exception) { return ShortEpgProbeCounts("INVALID_JSON") }
    val rows = (root as? JsonObject)?.get("epg_listings") as? JsonArray
        ?: return ShortEpgProbeCounts("INVALID_SHAPE")
    var objects = 0; var times = 0; var spans = 0; var titles = 0; var valid = 0
    val safeRows = mutableListOf<XtreamApi.ShortEpgListing>()
    fun JsonElement?.seconds() = (this as? JsonPrimitive)?.contentOrNull?.substringBefore('.')?.toLongOrNull()
    for (item in rows) {
        val row = item as? JsonObject
        if (row == null) { objects++; continue }
        val start = row["start_timestamp"].seconds(); val stop = row["stop_timestamp"].seconds()
        if (start == null || stop == null) { times++; continue }
        if (start <= 0 || stop <= start || stop > Long.MAX_VALUE / 1000) { spans++; continue }
        // Reuse the exact production decoder/title/duplicate rules on this one bounded row.
        val parsed = parseShortEpg(buildJsonObject { put("epg_listings", JsonArray(listOf(item))) })
        if (parsed.isEmpty()) titles++ else { valid++; safeRows += parsed }
    }
    val accepted = safeRows.distinctBy { it.startUtcMillis }
    val inWindow = accepted.count { it.endUtcMillis > now - 86_400_000L && it.startUtcMillis < now + 2 * 86_400_000L }
    return ShortEpgProbeCounts(encountered = rows.size, invalidObject = objects, invalidTime = times,
        invalidSpan = spans, missingTitle = titles, accepted = accepted.size,
        duplicateStarts = (valid - accepted.size).coerceAtLeast(0),
        current = accepted.count { it.startUtcMillis <= now && it.endUtcMillis > now },
        future = accepted.count { it.startUtcMillis > now }, past = accepted.count { it.endUtcMillis <= now },
        outOfWindow = accepted.size - inWindow, usableInWindow = inWindow)
}
