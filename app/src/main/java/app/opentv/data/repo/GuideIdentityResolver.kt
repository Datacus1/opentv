/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.parser.ChannelNameNormalizer

/** Identity matching is separate from schedule availability. No prefix or cross-country guesses. */
class GuideIdentityResolver(
    channels: List<Channel>, feeds: List<EpgFeed>, aliases: List<EpgChannelAlias>,
    populated: Set<String>, private val checkCancellation: () -> Unit = {},
) {
    data class Decision(val id: String?, val reason: String, val rejectedProviderId: Boolean = false)
    private data class Scope(val source: Long, val publicOnly: Boolean, val region: String?, val include4k: Boolean)
    private data class DecisionKey(val source: Long, val name: String, val providerId: String?, val shortKey: String?)
    private val providerFeedSources = feeds.filter { it.enabled && !it.deleting }
        .associate { it.id to it.providerSourceId }
    private val sharedIds = channels.filter { !it.epgChannelId.isNullOrBlank() }
        .groupBy { it.sourceId to it.epgChannelId!!.trim() }
        .filterValues { rows -> rows.map { conflictKey(it) }.distinct().size > 1 }.keys
    private val quarantinedIds = sharedIds.map { it.second }.toHashSet()
    // Alias labels can themselves be the last victim of a reused id. Quarantine the whole
    // conflicted identity, including name fallback, rather than trusting that last label.
    private val usable = aliases.filter { it.feedId in providerFeedSources && it.epgId in populated &&
        it.epgId.trim() !in quarantinedIds }
    private val indexes = HashMap<Scope, EpgMatcher.Index>()
    private val channelIdentities = HashMap<String, Pair<String, String?>>()
    internal fun nameIdentity(channel: Channel): Pair<String, String?> =
        channelIdentities.getOrPut(channel.name) { identity(channel.name) }
    private val shortAliases = usable.filter { it.epgId.startsWith("opentv.provider-short.") }
        .groupBy { it.epgId }
    private val aliasIdentities = usable.associateWith { identity(it.displayName) }
    private val aliasRegions = usable.associateWith { alias ->
        aliasIdentities.getValue(alias).second ?: countryFromId(alias.epgId)
    }
    // Request-scoped schedules (including legacy unqualified ones) cannot prove a name match.
    private val preparedAliases = usable.filterNot { it.epgId in shortAliases }.mapIndexed { i, alias ->
        if (i % 64 == 0) checkCancellation()
        alias to EpgMatcher.prepareAlias(alias.epgId, aliasIdentities.getValue(alias).first)
    }
    private val decisions = HashMap<DecisionKey, Decision>()

    private fun index(scope: Scope) = indexes.getOrPut(scope) {
        checkCancellation()
        EpgMatcher.buildPreparedIndex(preparedAliases.filter { (alias, _) ->
            (if (scope.publicOnly) providerFeedSources[alias.feedId] == null
                else providerFeedSources[alias.feedId] == scope.source) &&
                (scope.region == null || aliasRegions[alias] == scope.region ||
                    (aliasRegions[alias] == null && !scope.region.startsWith("REGION:"))) &&
                (scope.include4k || !FOUR_K.containsMatchIn(alias.displayName))
        }.map { it.second }, checkCancellation)
    }

    fun resolve(channel: Channel): Decision {
        val (key, region) = nameIdentity(channel)
        val shortEligible = key in EpgRepository.PROVIDER_SHORT_EPG_GROUP_KEYS
        val shortKey = if (shortEligible) ChannelGrouping.shortGuideKey(channel) else null
        return decisions.getOrPut(DecisionKey(channel.sourceId, channel.name, channel.epgChannelId,
            shortKey)) {
            val providerId = channel.epgChannelId?.takeIf(String::isNotBlank)
            val shared = (channel.sourceId to providerId?.trim()) in sharedIds
            val acceptedId = providerId.takeUnless { shared }
            // Only an exact declared id may identify an imported foreign network; a name
            // fallback must keep the channel's own country or its explicit id country hint.
            if (acceptedId != null) {
                index(Scope(channel.sourceId, false, null, true)).matchId(acceptedId)?.let {
                    return@getOrPut Decision(it, "SOURCE_PROVIDER_ID", shared)
                }
                index(Scope(channel.sourceId, true, null, true)).matchId(acceptedId)?.let {
                    return@getOrPut Decision(it, "PROVIDER_ID", shared)
                }
            }
            val nameRegion = region ?: countryFromId(acceptedId.orEmpty())
            val include4k = FOUR_K.containsMatchIn(channel.name)
            val preferred = index(Scope(channel.sourceId, false, nameRegion, include4k))
            val fallback = index(Scope(channel.sourceId, true, nameRegion, include4k))
            if (shortEligible) {
                // Provenance is the exact short-guide request group, not its ambiguous label.
                val shortId = EpgRepository.providerShortEpgId(channel.sourceId, key,
                    shortKey!!)
                if (shortAliases[shortId]?.any { providerFeedSources[it.feedId] == channel.sourceId } == true) {
                    return@getOrPut Decision(shortId, "SOURCE_SHORT_GUIDE_IDENTITY", shared)
                }
            }
            preferred.matchProvider(key, null)?.let {
                return@getOrPut Decision(it, "SOURCE_EXACT_IDENTITY", shared)
            }
            fallback.matchProvider(key, null)?.let {
                return@getOrPut Decision(it, "EXACT_OR_REVIEWED_ALIAS", shared)
            }
            Decision(null, if (shared) "CONFLICTING_SHARED_PROVIDER_ID" else
                if (preferred.hasAmbiguousPrefix(key) || fallback.hasAmbiguousPrefix(key))
                    "AMBIGUOUS_IDENTITY" else "NO_POPULATED_IDENTITY", shared)
        }
    }

    companion object {
        private val COUNTRY_SUFFIX = Regex("(?i)[._-]([a-z]{2})(?:[0-9]+)?$")
        private val FOUR_K = Regex("(?i)\\b(?:4K|UHD|2160P)\\b")
        private val QUALITY_PREFIX = Regex("(?i)^\\s*(US|CA|UK)-S\\s*[:|]\\s*")
        private val LOCAL_STATION = Regex("^\\s*([A-Z]{2})\\s*\\|[^|]+\\|")
        private val TEAM_PREFIX = Regex("(?i)^\\s*TEAM\\s*[:|]\\s*")
        private val NATIONAL_STATIONS = mapOf("abceast" to "ABCWABC.us", "abcwest" to "ABCKABC.us",
            "foxeast" to "FOXWNYW.us", "foxwest" to "FOXKTTV.us",
            "nbceast" to "NBCWNBC.us", "nbcwest" to "NBCKNBC.us",
            "cbseast" to "CBSWCBS.us", "cbswest" to "CBSKCBS.us")
        private val US_STATES = setOf("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS",
            "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA",
            "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC")
        private val COUNTRIES = java.util.Locale.getISOCountries().toSet()
        private fun canonicalRegion(value: String?) = when (value?.uppercase()) {
            "UK", "GBR" -> "GB"; "USA" -> "US"; "CAN" -> "CA"; "AUS" -> "AU"
            "CAR" -> "REGION:CARIBBEAN"; "LA" -> "REGION:LATIN_AMERICA"
            else -> value?.uppercase()?.takeIf { it in COUNTRIES }
        }
        fun identity(raw: String): Pair<String, String?> {
            // Provider's observed US-S: sports decoration is not the channel letter S.
            val cleaned = TEAM_PREFIX.replace(QUALITY_PREFIX.replace(raw) {
                "${it.groupValues[1].uppercase()}: " }, "")
            val normal = ChannelNameNormalizer.normalize(cleaned)
            val state = LOCAL_STATION.find(raw)?.groupValues?.get(1)
            return normal.groupKey to if (state in US_STATES) "US" else canonicalRegion(normal.region)
        }
        internal fun countryFromId(id: String) = canonicalRegion(COUNTRY_SUFFIX.find(id)?.groupValues?.get(1))
        private fun conflictKey(channel: Channel): String {
            val key = identity(channel.name).first
            val id = channel.epgChannelId!!.trim()
            // These national/local pairs are already explicit in this provider's metadata;
            // preserve their known station identity without inventing a new regional join.
            val stationKey = ChannelNameNormalizer.groupKeyOf(id.substringBeforeLast('.'))
            if (NATIONAL_STATIONS[key]?.equals(id, true) == true ||
                (LOCAL_STATION.containsMatchIn(channel.name) && key.endsWith(stationKey))) return id.lowercase()
            return EpgMatcher.reviewedIdentityKey(key)
        }
    }
}
