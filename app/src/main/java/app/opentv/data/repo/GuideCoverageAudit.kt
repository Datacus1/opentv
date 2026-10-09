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
        checkCancellation: () -> Unit = {},
    ): List<GuideChannelAudit> {
        checkCancellation()
        val enabledIds = feeds.filter { it.enabled && !it.deleting }.mapTo(HashSet()) { it.id }
        val disabledIds = feeds.filter { !it.enabled && !it.deleting }.mapTo(HashSet()) { it.id }
        val enabledCoverage = coverage.filter { it.feedId in enabledIds }.groupBy { it.epgChannelId }
        val disabledCoverage = coverage.filter { it.feedId in disabledIds }.groupBy { it.epgChannelId }
        val populated = enabledCoverage.filterValues { rows -> rows.any { it.totalRows > 0 } }.keys
        val usableAliases = aliases.filter { it.feedId in enabledIds && it.epgId in populated }
        val index = EpgMatcher.buildIndex(usableAliases.map { it.epgId to it.displayName }, checkCancellation)
        val disabledIndex = EpgMatcher.buildIndex(
            aliases.filter { it.feedId in disabledIds }.map { it.epgId to it.displayName },
            checkCancellation,
        )
        // Quality variants frequently repeat a provider id or normalized station name. Null
        // matches need an explicit wrapper: Map.getOrPut with null would recompute each miss.
        data class MatchResult(val id: String?, val ambiguous: Boolean)
        val enabledMatches = HashMap<Pair<String, String?>, MatchResult>()
        val disabledMatches = HashMap<Pair<String, String?>, MatchResult>()
        fun match(index: EpgMatcher.Index, cache: MutableMap<Pair<String, String?>, MatchResult>,
            key: String, providerId: String?): MatchResult = cache.getOrPut(key to providerId) {
            val id = index.matchProvider(key, providerId)
            MatchResult(id, id == null && index.hasAmbiguousPrefix(key))
        }
        val normalizedNames = HashMap<String, String>()
        fun normalized(name: String) = normalizedNames.getOrPut(name) {
            ChannelNameNormalizer.normalize(name).groupKey
        }
        fun logicalKey(channel: Channel) = channel.sourceId to channel.groupKey.ifBlank {
            normalized(channel.name).ifBlank { "id:${channel.id}" }
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
        checkCancellation()
        return channels.sortedBy { it.id }.mapIndexed { position, channel ->
            if (position % 64 == 0) checkCancellation()
            val key = normalized(channel.name)
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
            val suggestedMatch = match(index, enabledMatches, key, channel.epgChannelId)
            val suggested = suggestedMatch.id
            val unappliedUsefulMatch = override == null && suggested != null && suggested !in candidates &&
                (hasCurrent(suggested) || hasUpcoming(suggested))
            val status = when {
                chosen != null && hasCurrent(chosen) -> GuideCoverageStatus.ACTUAL_CURRENT
                unappliedUsefulMatch -> GuideCoverageStatus.CANDIDATE_MATCH_NOT_APPLIED
                chosen != null && hasUpcoming(chosen) -> GuideCoverageStatus.UPCOMING_ONLY
                chosen != null && hasRows(chosen) -> GuideCoverageStatus.EXPIRED
                candidates.any { it in disabledCoverage } ||
                    match(disabledIndex, disabledMatches, key, channel.epgChannelId).id != null ->
                    GuideCoverageStatus.DISABLED_FEED
                suggestedMatch.ambiguous -> GuideCoverageStatus.AMBIGUOUS
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
            rows = rows.subList(boundedOffset,
                (boundedOffset + limit.coerceIn(1, 200)).coerceAtMost(rows.size)).toList(),
            counts = rows.groupingBy { it.status }.eachCount(),
        )
    }

    private fun safeLabel(value: String): String = value
        .replace(URI, "[redacted link]")
        .filterNot { it.isISOControl() }.take(160)

    private val URI = Regex("(?i)(?:[a-z][a-z0-9+.-]*://|https?%3a(?:%2f){2})\\S+")
}
