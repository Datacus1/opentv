/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Channel
import app.opentv.data.parser.ChannelNameNormalizer

/** Runtime-only identity boundary. A normalized name alone is not proof of shared programming. */
object ChannelGrouping {
    private fun identity(channel: Channel, rawIdentity: Pair<String, String?> = GuideIdentityResolver.identity(channel.name)): List<String?> {
        val (name, region) = rawIdentity
        val normal = if (region == null) ChannelNameNormalizer.normalize(channel.name) else null
        val matched = channel.matchedEpgId?.takeIf(String::isNotBlank)
        val override = channel.epgOverrideId?.takeIf(String::isNotBlank)
        return listOf(
            EpgMatcher.reviewedIdentityKey(name).ifBlank { "id:${channel.id}" },
            region ?: normal?.region?.let { "TAG:$it" }
                ?: GuideIdentityResolver.countryFromId(override ?: matched.orEmpty()),
            override, matched,
            // Never let a rejected/missing match borrow an accepted sibling's listing.
            if (override == null && matched == null) channel.epgChannelId?.takeIf(String::isNotBlank) else null,
            if (override == null && matched == null && channel.epgChannelId.isNullOrBlank()) channel.id.toString() else null,
            // Unmatched UHD event feeds are not known to simulcast their ordinary namesake.
            if (override == null && matched == null && channel.qualityRank >= 400) "UHD" else null,
        )
    }

    // Length framing keeps literal, case-sensitive IDs distinct (including delimiters).
    private fun token(parts: List<String?>): String = parts.joinToString("") {
        if (it == null) "N;" else "S${it.length}:$it"
    }

    /** String keys are also saveable Compose lazy-list keys. Source is always part of a UI row. */
    fun key(channel: Channel): String = token(listOf(channel.sourceId.toString()) + identity(channel))

    internal fun key(channel: Channel, rawIdentity: Pair<String, String?>): String =
        token(listOf(channel.sourceId.toString()) + identity(channel, rawIdentity))

    fun groups(channels: List<Channel>): Map<String, List<Channel>> = channels.groupBy(::key)

    fun variantsFor(channel: Channel, candidates: List<Channel>): List<Channel> {
        val target = key(channel)
        return candidates.filter { key(it) == target }
    }

    /** Independent of recomputed matches, so a short-guide refresh cannot change its own key. */
    fun shortGuideKey(channel: Channel): String = key(channel.copy(matchedEpgId = null, epgOverrideId = null))

    /** Cross-provider recording is allowed only for the same explicitly accepted identity. */
    fun sameRecordingIdentity(channel: Channel, candidate: Channel): Boolean =
        channel.epgCandidates.any(String::isNotBlank) && candidate.epgCandidates.any(String::isNotBlank) &&
            identity(channel) == identity(candidate)
}
