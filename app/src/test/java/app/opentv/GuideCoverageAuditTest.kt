/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.db.GuideCoverageRow
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.repo.GuideCoverageAudit
import app.opentv.data.repo.GuideCoverageStatus
import app.opentv.data.repo.chooseGuideCandidate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCoverageAuditTest {
    private fun channel(id: Long = 1, name: String = "ABC East", epgId: String? = "abc") = Channel(
        id = id, sourceId = 7, streamId = "private-stream-id", name = name,
        displayName = name, groupKey = "abceast", categoryId = "live", logoUrl = "https://private/logo",
        epgChannelId = epgId, matchedEpgId = epgId, streamUrl = "https://private/user/password/channel", number = null,
    )

    private fun coverage(
        id: String = "abc", feedId: Long = 1, total: Int = 2, current: Int = 1, upcoming: Int = 1,
    ) = GuideCoverageRow(
        feedId = feedId, epgChannelId = id, totalRows = total,
        currentRows = current, upcomingRows = upcoming,
        visibleRows = current + upcoming,
        lastEndMillis = if (current == 0 && upcoming == 0) 99L else 300L,
        nextStartMillis = if (upcoming > 0) 200L else null,
    )

    private val enabled = EpgFeed(id = 1, name = "Private feed", url = "https://private/guide")
    private val disabled = EpgFeed(id = 2, name = "Disabled", enabled = false)
    private fun alias(id: String, name: String, feedId: Long = 1) =
        EpgChannelAlias(feedId, id, name, name.lowercase().replace(" ", ""))

    @Test
    fun `audit distinguishes current upcoming expired disabled and unresolved without upstream claims`() {
        val rows = GuideCoverageAudit.audit(
            channels = listOf(channel(1), channel(2, epgId = "future"), channel(3, epgId = "expired"),
                channel(4, "Different", "disabled"), channel(5, "Unknown", "unknown")),
            feeds = listOf(enabled, disabled), aliases = emptyList(),
            coverage = listOf(coverage(), coverage("future", current = 0),
                coverage("expired", current = 0, upcoming = 0), coverage("disabled", feedId = 2)),
            storageBlocked = true,
        )
        assertEquals(listOf(GuideCoverageStatus.ACTUAL_CURRENT, GuideCoverageStatus.UPCOMING_ONLY,
            GuideCoverageStatus.EXPIRED, GuideCoverageStatus.DISABLED_FEED,
            GuideCoverageStatus.UNRESOLVED), rows.map { it.status })
        assertTrue(rows.all { it.storageBlocked })
    }

    @Test
    fun `unapplied populated name match is distinguished from missing provider schedule`() {
        val row = GuideCoverageAudit.audit(listOf(channel(epgId = "broken")), listOf(enabled),
            listOf(alias("public", "ABC National Feed")), listOf(coverage("public")), false).single()
        assertEquals(GuideCoverageStatus.CANDIDATE_MATCH_NOT_APPLIED, row.status)
        assertEquals("public", row.suggestedGuideId)
        assertNull(row.chosenGuideId)
    }

    @Test
    fun `ambiguous names and empty aliases are not guessed`() {
        val row = GuideCoverageAudit.audit(listOf(channel(name = "BBC One", epgId = null)),
            listOf(enabled), listOf(alias("london", "BBC One London"), alias("wales", "BBC One Wales")),
            listOf(coverage("london"), coverage("wales")), false).single()
        assertEquals(GuideCoverageStatus.UNRESOLVED, row.status)
        val empty = GuideCoverageAudit.audit(listOf(channel(name = "BBC One", epgId = null)),
            listOf(enabled), listOf(alias("london", "BBC One London"), alias("wales", "BBC One Wales")),
            listOf(coverage("london")), false).single()
        assertEquals(GuideCoverageStatus.UNRESOLVED, empty.status)
        assertNull(empty.suggestedGuideId)
    }

    @Test
    fun `enabled copy of same guide id wins over disabled feed`() {
        val row = GuideCoverageAudit.audit(listOf(channel()), listOf(enabled, disabled), emptyList(),
            listOf(coverage(), coverage(feedId = 2)), false).single()
        assertEquals(GuideCoverageStatus.ACTUAL_CURRENT, row.status)
    }

    @Test
    fun `deleting feed data is not classified as a disabled usable guide`() {
        val row = GuideCoverageAudit.audit(listOf(channel()), listOf(enabled.copy(deleting = true)),
            listOf(alias("abc", "ABC East")), listOf(coverage()), false).single()
        assertEquals(GuideCoverageStatus.UNRESOLVED, row.status)
    }

    @Test
    fun `audit keeps all raw variants hidden rows and sources without leaking credentials`() {
        val channels = listOf(channel(2).copy(hidden = true), channel(1),
            channel(3).copy(sourceId = 8, displayName = "Bad https://private/user/password label"))
        val rows = GuideCoverageAudit.audit(channels, listOf(enabled), emptyList(), listOf(coverage()), false)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.channelId })
        assertEquals(8L, rows.last().sourceId)
        assertTrue(rows[1].hidden)
        val encoded = Json.encodeToString(GuideCoverageAudit.page(rows, 0))
        assertFalse(encoded.contains("https://"))
        assertFalse(encoded.contains("user/password"))
        assertFalse(encoded.contains("private-stream-id"))
        assertFalse(encoded.contains("Private feed"))
    }

    @Test
    fun `page clamps bounds and never omits raw variants from totals`() {
        val rows = GuideCoverageAudit.audit((1L..250L).map { channel(it) }, listOf(enabled),
            emptyList(), listOf(coverage()), false)
        val first = GuideCoverageAudit.page(rows, -1, 1000)
        assertEquals(250, first.total)
        assertEquals(0, first.offset)
        assertEquals(200, first.rows.size)
        assertEquals(250, first.counts[GuideCoverageStatus.ACTUAL_CURRENT])
        assertTrue(GuideCoverageAudit.page(rows, 1000).rows.isEmpty())
        assertEquals(1, GuideCoverageAudit.page(rows, 0, -1).rows.size)
    }

    @Test
    fun `candidate helper prefers current data over provider past or future data`() {
        val channel = channel().copy(matchedEpgId = "current")
        val now = 100L
        val past = Programme(1, "abc", 0, 50, "Past")
        val next = Programme(1, "abc", 200, 300, "Next")
        val live = Programme(1, "current", 50, 150, "Live")
        assertEquals("current", chooseGuideCandidate(listOf(channel),
            mapOf("abc" to listOf(past, next), "current" to listOf(live)), now))
    }

    @Test
    fun `populated manual override remains authoritative and empty override can fall back`() {
        val channel = channel().copy(epgOverrideId = "manual")
        val live = Programme(1, "abc", 50, 150, "Live")
        val manual = Programme(1, "manual", 200, 300, "Manual next")
        assertEquals("manual", chooseGuideCandidate(listOf(channel),
            mapOf("manual" to listOf(manual), "abc" to listOf(live)), 100))
        assertEquals("abc", chooseGuideCandidate(listOf(channel),
            mapOf("manual" to emptyList(), "abc" to listOf(live)), 100))
        val row = GuideCoverageAudit.audit(listOf(channel), listOf(enabled),
            listOf(alias("public", "ABC National Feed")),
            listOf(coverage("manual", current = 0), coverage("public")), false).single()
        assertEquals(GuideCoverageStatus.UPCOMING_ONLY, row.status)
        assertEquals("manual", row.chosenGuideId)
    }

    @Test
    fun `all identical quality variants can supply guide and lower quality current wins`() {
        val high = channel(1, epgId = "empty").copy(qualityRank = 300)
        val duplicate = high.copy(id = 2, epgChannelId = "live", matchedEpgId = "live")
        val live = Programme(1, "live", 50, 150, "Live")
        assertEquals("live", chooseGuideCandidate(listOf(high, duplicate), mapOf("live" to listOf(live)), 100))
        val lower = duplicate.copy(id = 3, qualityRank = 200)
        assertEquals("live", chooseGuideCandidate(listOf(high, lower), mapOf("live" to listOf(live)), 100))
        val rows = GuideCoverageAudit.audit(listOf(high, duplicate), listOf(enabled), emptyList(),
            listOf(coverage("live")), false)
        assertEquals(GuideCoverageStatus.UNRESOLVED, rows.first().status)
        assertTrue(rows.all { it.logicalCurrentAvailable })
        assertTrue(rows.all { it.logicalGuideId == "live" })
    }

    @Test
    fun `candidate helper uses half open current intervals and empty lists never select`() {
        val channel = channel().copy(matchedEpgId = "next")
        val ended = Programme(1, "abc", 0, 100, "Ended")
        val started = Programme(1, "next", 100, 200, "Started")
        assertEquals("next", chooseGuideCandidate(listOf(channel),
            mapOf("abc" to listOf(ended), "next" to listOf(started)), 100))
        assertNull(chooseGuideCandidate(listOf(channel), mapOf("abc" to emptyList()), 100))
    }

    @Test
    fun `direction and plus distinction are retained by audit matcher`() {
        val channels = listOf(channel(1, "ABC West", null), channel(2, "AMC+", null))
        val rows = GuideCoverageAudit.audit(channels, listOf(enabled),
            listOf(alias("east", "ABC National Feed"), alias("amc", "AMC HD")),
            listOf(coverage("east"), coverage("amc")), false)
        assertTrue(rows.all { it.status == GuideCoverageStatus.UNRESOLVED })
        assertTrue(rows.all { it.suggestedGuideId == null })
    }

    @Test
    fun `hidden variants and another source never falsely populate a logical visible row`() {
        val visible = channel(1, epgId = "missing")
        val hidden = channel(2).copy(hidden = true)
        val otherSource = channel(3).copy(sourceId = 8)
        val rows = GuideCoverageAudit.audit(listOf(visible, hidden, otherSource), listOf(enabled),
            emptyList(), listOf(coverage()), false)
        assertFalse(rows.first().logicalCurrentAvailable)
        assertNull(rows.first().logicalGuideId)
        assertTrue(rows.last().logicalCurrentAvailable)
    }

    @Test
    fun `catalog scale audit retains all 6600 rows and pages against 1000 aliases`() {
        val aliases = (0 until 1000).map { alias("guide-$it.us2", "Guide Station $it") }
        val coverage = (0 until 1000).map { coverage("guide-$it.us2") }
        val channels = (0 until 6600).map { i ->
            channel(i.toLong() + 1, "Unknown Provider Station $i", if (i < 1000) "guide-$i.us2" else null)
                .copy(groupKey = "unknownproviderstation$i")
        }
        var checks = 0
        val started = System.nanoTime()
        val rows = GuideCoverageAudit.audit(channels, listOf(enabled), aliases, coverage, true) { checks++ }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(6600, rows.size)
        assertEquals(1000, rows.count { it.status == GuideCoverageStatus.ACTUAL_CURRENT })
        assertEquals(5600, rows.count { it.status == GuideCoverageStatus.UNRESOLVED })
        assertTrue(checks > 100) // Cancellation is observed while building indexes and channel rows.
        val exportedIds = (0 until 6600 step 200).flatMap { GuideCoverageAudit.page(rows, it, 200).rows }
            .map { it.channelId }
        assertEquals(rows.map { it.channelId }, exportedIds)
        // Generous PC regression budget, not a claim about Shield hardware latency.
        assertTrue("Catalog audit took ${elapsed}ms", elapsed < 8000)
        println("Guide audit synthetic 6600 channels / 1000 aliases: ${elapsed}ms")
    }

    @Test
    fun `catalog scale audit propagates cancellation during index preparation`() {
        var checks = 0
        try {
            GuideCoverageAudit.audit(listOf(channel()), listOf(enabled),
                (0 until 1000).map { alias("guide-$it", "Guide $it") },
                (0 until 1000).map { coverage("guide-$it") }, false) {
                if (++checks == 4) throw CancellationException("stop")
            }
            throw AssertionError("Cancellation swallowed")
        } catch (_: CancellationException) {
            assertEquals(4, checks)
        }
    }

    @Test
    fun `catalog audit propagates cancellation while emitting exhaustive rows`() {
        var checks = 0
        try {
            GuideCoverageAudit.audit((1L..6600).map { channel(it) }, listOf(enabled), emptyList(),
                listOf(coverage()), false) {
                if (++checks == 7) throw CancellationException("stop")
            }
            throw AssertionError("Cancellation swallowed")
        } catch (_: CancellationException) {
            assertEquals(7, checks)
        }
    }
}
