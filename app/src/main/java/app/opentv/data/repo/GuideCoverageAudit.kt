/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.GuideCoverageRow
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.parser.ChannelNameNormalizer
import kotlinx.serialization.Serializable

/** Prefer a current listing, without silently overriding an explicit, populated manual choice. */
fun chooseGuideCandidate(
    variants: List<Channel>,
    byEpgChannel: Map<String, List<Programme>>,
    nowUtcMillis: Long,
): String? {
    // ALL variants participate: identical-quality duplicate streams can have different guide ids.
    val ordered = variants.sortedByDescending { it.qualityRank }
    ordered.asSequence().mapNotNull { it.epgOverrideId?.takeIf(String::isNotBlank) }
        .firstOrNull { !byEpgChannel[it].isNullOrEmpty() }?.let { return it }
    val candidates = ordered.flatMap { it.epgCandidates }.filter(String::isNotBlank).distinct()
    return candidates.firstOrNull { id -> byEpgChannel[id]?.any { it.isLiveAt(nowUtcMillis) } == true }
        ?: candidates.firstOrNull { !byEpgChannel[it].isNullOrEmpty() }
}

/** No status claims that a schedule does not exist upstream: absent matches remain unresolved. */
@Serializable
enum class GuideCoverageStatus {
    ACTUAL_CURRENT, UPCOMING_ONLY, EXPIRED, CANDIDATE_MATCH_NOT_APPLIED,
    DISABLED_FEED, AMBIGUOUS, UNRESOLVED,
}

/** Deliberately not Channel/Source/EpgFeed: those models contain private URLs and credentials. */
@Serializable
data class GuideChannelAudit(
    val channelId: Long,
    val sourceId: Long,
    val name: String,
    val groupKey: String,
    val hidden: Boolean,
    val qualityRank: Int,
    val status: GuideCoverageStatus,
    val chosenGuideId: String?,
    val suggestedGuideId: String?,
    val storageBlocked: Boolean,
    val nextStartMillis: Long?,
    val lastEndMillis: Long?,
    /** A sibling stream may populate the guide row even if this individual variant has no id. */
    val logicalCurrentAvailable: Boolean = false,
    val logicalGuideId: String? = null,
)

@Serializable
data class GuideCoveragePage(
    val total: Int,
    val offset: Int,
    val rows: List<GuideChannelAudit>,
    val counts: Map<GuideCoverageStatus, Int>,
    /** This audit anchors the visible window at now, not a user's historical timeline position. */
    val liveWindowHours: Int = 12,
)

object GuideCoverageAudit {
    /** Read-only, exhaustive raw-variant audit; callers may summarize (sourceId, groupKey) separately. */
    fun audit(
        channels: List<Channel>,
        feeds: List<EpgFeed>,
        aliases: List<EpgChannelAlias>,
        coverage: List<GuideCoverageRow>,
        storageBlocked: Boolean,
    ): List<GuideChannelAudit> {
        val enabledIds = feeds.filter { it.enabled && !it.deleting }.mapTo(HashSet()) { it.id }
        val disabledIds = feeds.filter { !it.enabled && !it.deleting }.mapTo(HashSet()) { it.id }
        val enabledCoverage = coverage.filter { it.feedId in enabledIds }.groupBy { it.epgChannelId }
        val disabledCoverage = coverage.filter { it.feedId in disabledIds }.groupBy { it.epgChannelId }
        val populated = enabledCoverage.filterValues { rows -> rows.any { it.totalRows > 0 } }.keys
        val usableAliases = aliases.filter { it.feedId in enabledIds && it.epgId in populated }
        val index = EpgMatcher.buildIndex(usableAliases.map { it.epgId to it.displayName })
        // Normalize once, not once per unmatched provider channel on the TV's CPU.
        val diagnosticKeys = usableAliases.flatMap { alias ->
            listOf(alias.displayName, alias.epgId).map { name ->
                alias.epgId to ChannelNameNormalizer.normalize(name).groupKey
            }
        }.distinct()
        val disabledIndex = EpgMatcher.buildIndex(
            aliases.filter { it.feedId in disabledIds }.map { it.epgId to it.displayName },
        )
        fun logicalKey(channel: Channel) = channel.sourceId to channel.groupKey.ifBlank {
            ChannelNameNormalizer.normalize(channel.name).groupKey.ifBlank { "id:${channel.id}" }
        }
        val logicalCandidates = channels.filterNot { it.hidden }.groupBy(::logicalKey).mapValues { (_, variants) ->
            val ordered = variants.sortedByDescending { it.qualityRank }
            val explicit = ordered.mapNotNull { it.epgOverrideId }.firstOrNull { id ->
                enabledCoverage[id]?.any { it.visibleRows > 0 } == true
            }
            explicit ?: ordered.flatMap { it.epgCandidates }.firstOrNull { id ->
                enabledCoverage[id]?.any { it.currentRows > 0 } == true
            }
        }
        return channels.sortedBy { it.id }.map { channel ->
            val key = ChannelNameNormalizer.normalize(channel.name).groupKey
            val candidates = channel.epgCandidates.filter(String::isNotBlank).distinct()
            fun hasCurrent(id: String) = enabledCoverage[id]?.any { it.currentRows > 0 } == true
            fun hasUpcoming(id: String) = enabledCoverage[id]?.any { it.upcomingRows > 0 } == true
            fun hasRows(id: String) = enabledCoverage[id]?.any { it.totalRows > 0 } == true
            // A manual override with useful data remains authoritative, matching the UI helper.
            val override = channel.epgOverrideId?.takeIf { id ->
                enabledCoverage[id]?.any { it.visibleRows > 0 } == true
            }
            val chosen = override ?: candidates.firstOrNull(::hasCurrent)
                ?: candidates.firstOrNull(::hasUpcoming) ?: candidates.firstOrNull(::hasRows)
            val suggested = index.matchProvider(key, channel.epgChannelId)
            val unappliedUsefulMatch = override == null && suggested != null && suggested !in candidates &&
                (hasCurrent(suggested) || hasUpcoming(suggested))
            val status = when {
                chosen != null && hasCurrent(chosen) -> GuideCoverageStatus.ACTUAL_CURRENT
                unappliedUsefulMatch -> GuideCoverageStatus.CANDIDATE_MATCH_NOT_APPLIED
                chosen != null && hasUpcoming(chosen) -> GuideCoverageStatus.UPCOMING_ONLY
                chosen != null && hasRows(chosen) -> GuideCoverageStatus.EXPIRED
                candidates.any { it in disabledCoverage } ||
                    disabledIndex.matchProvider(key, channel.epgChannelId) != null ->
                    GuideCoverageStatus.DISABLED_FEED
                suggested == null && ambiguousName(key, diagnosticKeys) -> GuideCoverageStatus.AMBIGUOUS
                else -> GuideCoverageStatus.UNRESOLVED
            }
            val chosenRows = chosen?.let(enabledCoverage::get).orEmpty()
            val logicalGuideId = logicalCandidates[logicalKey(channel)]
            GuideChannelAudit(
                channelId = channel.id, sourceId = channel.sourceId,
                name = safeLabel(channel.displayName), groupKey = safeLabel(channel.groupKey),
                hidden = channel.hidden, qualityRank = channel.qualityRank, status = status,
                chosenGuideId = chosen?.let(::safeLabel),
                suggestedGuideId = suggested?.let(::safeLabel), storageBlocked = storageBlocked,
                nextStartMillis = chosenRows.mapNotNull { it.nextStartMillis }.minOrNull(),
                lastEndMillis = chosenRows.maxOfOrNull { it.lastEndMillis },
                logicalCurrentAvailable = logicalGuideId != null && hasCurrent(logicalGuideId),
                logicalGuideId = logicalGuideId?.let(::safeLabel),
            )
        }
    }

    /** Clamp pages to avoid a remote diagnostic turning into one huge response. */
    fun page(rows: List<GuideChannelAudit>, offset: Int, limit: Int = 100): GuideCoveragePage {
        val boundedOffset = offset.coerceIn(0, rows.size)
        return GuideCoveragePage(
            total = rows.size, offset = boundedOffset,
            rows = rows.drop(boundedOffset).take(limit.coerceIn(1, 200)),
            counts = rows.groupingBy { it.status }.eachCount(),
        )
    }

    private fun ambiguousName(key: String, aliases: List<Pair<String, String>>): Boolean {
        if (key.length < 2) return false
        val ids = HashSet<String>()
        for ((id, aliasKey) in aliases) {
            if (aliasKey.length >= 2 &&
                key.contains("plus") == aliasKey.contains("plus") &&
                (aliasKey.startsWith(key) || key.startsWith(aliasKey))) {
                ids += id
                if (ids.size > 1) return true
            }
        }
        return false
    }

    private fun safeLabel(value: String): String = value
        .replace(URI, "[redacted link]")
        .filterNot { it.isISOControl() }.take(160)

    private val URI = Regex("(?i)(?:[a-z][a-z0-9+.-]*://|https?%3a(?:%2f){2})\\S+")
}
