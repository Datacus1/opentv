package app.opentv.data.repo

import app.opentv.data.db.GuideCoverageRow
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.parser.ChannelNameNormalizer
import kotlinx.serialization.Serializable

/** Allowlisted identity evidence, never provider models, URLs or programme contents. */
@Serializable
data class GuideIdentityChannel(
    val channelId: Long, val sourceId: Long, val name: String, val key: String,
    val region: String?, val providerId: String?, val matchedId: String?, val overrideId: String?,
)

@Serializable
data class GuideIdentityAlias(
    val feedId: Long, val guideId: String, val name: String, val enabled: Boolean,
    val providerSourceId: Long?, val totalRows: Int, val currentRows: Int, val upcomingRows: Int,
)

/** Programme identities independent of alias availability. Fingerprints preserve exact case/spacing. */
@Serializable
data class GuideIdentityCoverage(
    val feedId: Long, val guideId: String, val fingerprint: String, val lossyLabel: Boolean,
    val hasAlias: Boolean, val enabled: Boolean, val providerSourceId: Long?,
    val totalRows: Int, val currentRows: Int, val upcomingRows: Int,
)

@Serializable
data class GuideIdentityPage(
    val kind: String, val total: Int, val offset: Int,
    val channels: List<GuideIdentityChannel> = emptyList(),
    val aliases: List<GuideIdentityAlias> = emptyList(),
    val coverage: List<GuideIdentityCoverage> = emptyList(),
    val coverageComplete: Boolean = true,
    val coverageIdentitiesTotal: Int = 0,
)

data class GuideIdentitySnapshot(
    val channels: List<GuideIdentityChannel>, val aliases: List<GuideIdentityAlias>,
    val coverage: List<GuideIdentityCoverage> = emptyList(),
    val coverageComplete: Boolean = true, val coverageIdentitiesTotal: Int = coverage.size,
) {
    fun page(kind: String, offset: Int, limit: Int): GuideIdentityPage {
        require(kind == "channels" || kind == "aliases" || kind == "coverage")
        val total = when (kind) { "channels" -> channels.size; "aliases" -> aliases.size; else -> coverage.size }
        val start = offset.coerceIn(0, total)
        val end = (start + limit.coerceIn(1, 100)).coerceAtMost(total)
        return GuideIdentityPage(kind, total, start,
            channels = if (kind == "channels") channels.subList(start, end).toList() else emptyList(),
            aliases = if (kind == "aliases") aliases.subList(start, end).toList() else emptyList(),
            coverage = if (kind == "coverage") coverage.subList(start, end).toList() else emptyList(),
            coverageComplete = coverageComplete, coverageIdentitiesTotal = coverageIdentitiesTotal)
    }
}

object GuideIdentityAudit {
    internal const val MAX_COVERAGE_IDENTITIES = 20_000
    fun snapshot(channels: List<Channel>, feeds: List<EpgFeed>, aliases: List<EpgChannelAlias>,
        coverage: List<GuideCoverageRow>, checkCancellation: () -> Unit = {}): GuideIdentitySnapshot {
        val feedById = feeds.associateBy { it.id }
        val byIdentity = coverage.associateBy { it.feedId to it.epgChannelId }
        val aliasIds = aliases.mapTo(HashSet()) { it.feedId to it.epgId }
        fun safe(value: String?) = value?.let(GuideCoverageAudit::safeLabel)
        return GuideIdentitySnapshot(
            channels.sortedBy { it.id }.mapIndexed { i, channel ->
                if (i % 64 == 0) checkCancellation()
                // Redact before normalization: flattening a URI first would hide its scheme
                // from the redactor while retaining its credential-bearing path in the key.
                val normalized = ChannelNameNormalizer.normalize(safe(channel.name)!!)
                GuideIdentityChannel(channel.id, channel.sourceId, safe(channel.name)!!,
                    safe(normalized.groupKey)!!, safe(normalized.region), safe(channel.epgChannelId),
                    safe(channel.matchedEpgId), safe(channel.epgOverrideId))
            },
            aliases.sortedWith(compareBy({ it.feedId }, { it.epgId })).mapIndexed { i, alias ->
                if (i % 64 == 0) checkCancellation()
                val feed = feedById[alias.feedId]
                val rows = byIdentity[alias.feedId to alias.epgId]
                GuideIdentityAlias(alias.feedId, safe(alias.epgId)!!, safe(alias.displayName)!!,
                    feed?.let { it.enabled && !it.deleting } == true, feed?.providerSourceId,
                    rows?.totalRows ?: 0, rows?.currentRows ?: 0, rows?.upcomingRows ?: 0)
            },
            coverage.sortedWith(compareBy({ it.feedId }, { it.epgChannelId }))
                .take(MAX_COVERAGE_IDENTITIES).mapIndexed { i, row ->
                    if (i % 64 == 0) checkCancellation()
                    val feed = feedById[row.feedId]
                    val label = safe(row.epgChannelId)!!
                    GuideIdentityCoverage(row.feedId, label, GuideFeedDiagnostic.fingerprint(row.epgChannelId),
                        label != row.epgChannelId, (row.feedId to row.epgChannelId) in aliasIds,
                        feed?.let { it.enabled && !it.deleting } == true, feed?.providerSourceId,
                        row.totalRows, row.currentRows, row.upcomingRows)
                },
            coverageComplete = coverage.size <= MAX_COVERAGE_IDENTITIES,
            coverageIdentitiesTotal = coverage.size)
    }
}
