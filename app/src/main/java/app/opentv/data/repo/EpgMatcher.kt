/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.parser.ChannelNameNormalizer

/**
 * Joins provider channels to guide channels when nobody gave us a join key.
 *
 * The provider says `UK| BBC ONE FHD`. The guide says `BBC One` (id `bbc1.uk`). The only
 * bridge between them is the name, so both sides are pushed through
 * [ChannelNameNormalizer.groupKeyOf] and matched on the result.
 *
 * ## The design rule: prefer no match to a wrong match
 *
 * A missing guide entry reads as "provider doesn't do EPG" and can be fixed with a manual
 * override. A *wrong* one shows EastEnders against a sports channel, and the user reasonably
 * concludes the whole guide is broken. So the fuzzy tier only accepts a prefix match when it
 * is **unambiguous** — exactly one guide channel fits. `bbcone` will claim `BBC One` when
 * that is the only candidate, but if the guide carries `BBC One London` *and* `BBC One
 * Wales` and nothing plain, we refuse to guess between regions.
 */
object EpgMatcher {

    /** One guide channel, pre-normalised. */
    data class Alias(
        val epgId: String,
        /** Normalised display-name or id: `bbcone`, `bbc1uk`. */
        val key: String,
    )

    class Index internal constructor(
        private val exact: Map<String, String>,
        private val sortedKeys: List<Alias>,
    ) {
        /**
         * Matches a provider channel using its explicit XMLTV id before falling back to its
         * normalised display name. Providers frequently send a useful `tvg-id` even when their
         * on-screen name is abbreviated or decorated; ignoring it needlessly loses the strongest
         * automatic join key we have.
         */
        fun matchProvider(groupKey: String, providerEpgId: String?): String? {
            providerEpgId
                ?.takeIf { it.isNotBlank() }
                ?.let { id ->
                    matchExact(ChannelNameNormalizer.normalize(id).groupKey)
                        ?: matchExact(canonicalGuideIdKey(id))
                }
                ?.let { return it }

            // A small set of direction-preserving names for national feeds whose public XMLTV
            // labels cannot be inferred from the provider label. Keep these explicit: treating
            // every bare network as "East" would silently attach the wrong regional schedule.
            PROVIDER_KEY_ALIASES[groupKey]
                ?.firstNotNullOfOrNull { guideName ->
                    matchExact(ChannelNameNormalizer.normalize(guideName).groupKey)
                }
                ?.let { return it }

            // FAST platforms sometimes prepend their own brand to an otherwise exact channel
            // name (`Samsung FOX SOUL`). Strip only prefixes observed to behave this way and
            // still require the ordinary matcher to find one unambiguous guide channel.
            SAFE_PLATFORM_PREFIXES.firstNotNullOfOrNull { prefix ->
                groupKey.takeIf { it.startsWith(prefix) && it.length > prefix.length }
                    ?.removePrefix(prefix)
                    ?.let(::match)
            }?.let { return it }
            return match(groupKey)
        }

        /** Exact-only lookup for authoritative ids and direction-sensitive aliases. */
        private fun matchExact(groupKey: String): String? = exact[groupKey]

        /** Returns the epg id for a provider channel's group key, or null. */
        fun match(groupKey: String): String? {
            if (groupKey.length < MIN_KEY_LENGTH) return null

            exact[groupKey]?.let { return it }

            // Prefix tier: the provider name extends the guide name or vice versa
            // ("skysportsmainevent" vs "skysportsmainevt"; "bbcone" vs "bbconelondon").
            // Only accepted when every candidate agrees on a single epg id.
            var found: String? = null
            for (alias in sortedKeys) {
                val hit = (alias.key.length >= MIN_KEY_LENGTH) &&
                    (alias.key.startsWith(groupKey) || groupKey.startsWith(alias.key))
                if (!hit) continue
                if (found == null) {
                    found = alias.epgId
                } else if (found != alias.epgId) {
                    return null // ambiguous — refuse to guess
                }
            }
            return found
        }
    }

    /**
     * Builds the lookup from every `<channel>` element seen across every enabled guide.
     *
     * Both the display-name (`BBC One`) and the raw id (`bbc1.uk`) are indexed — providers
     * that *do* fill in `epg_channel_id` often use the id form, and it costs nothing to
     * accept either.
     */
    fun buildIndex(aliases: Iterable<Pair<String, String>>): Index {
        val exact = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        val all = ArrayList<Alias>()

        for ((epgId, name) in aliases) {
            val candidates = listOf(
                ChannelNameNormalizer.normalize(name).groupKey,
                ChannelNameNormalizer.normalize(epgId).groupKey,
                canonicalGuideIdKey(epgId),
            ).distinct()
            for (key in candidates) {
                // Full normalisation strips display-name quality markers; the additional
                // canonical id key tolerates only a terminal guide-feed revision.
                if (key.length < MIN_KEY_LENGTH) continue
                all += Alias(epgId, key)
                val existing = exact.putIfAbsent(key, epgId)
                if (existing != null && existing != epgId) {
                    // Two different guide channels normalise to the same key. Neither can
                    // be trusted for exact matching; drop the key rather than pick a side.
                    ambiguous += key
                }
            }
        }
        ambiguous.forEach(exact::remove)

        return Index(exact, all)
    }

    /**
     * Below this, keys are too generic to mean anything — `e4` is real, but `tv`, `hd` and
     * single letters match everything. 2 keeps `e4`/`5usa`-style names alive.
     */
    private const val MIN_KEY_LENGTH = 2

    /**
     * EPGShare periodically versions only the final country suffix (`.us` -> `.us2`). Ignore that
     * transport revision while retaining the full channel id, including East/West/Pacific.
     */
    private fun canonicalGuideIdKey(id: String): String {
        val withoutFeedRevision = GUIDE_ID_FEED_SUFFIX.replace(id.trim(), "")
        return ChannelNameNormalizer.normalize(withoutFeedRevision).groupKey
    }

    private val GUIDE_ID_FEED_SUFFIX = Regex(
        pattern = "[._-](?:us|ca|uk|gb|au|nz)[0-9]*$",
        option = RegexOption.IGNORE_CASE,
    )

    private val SAFE_PLATFORM_PREFIXES = listOf("samsung")

    /**
     * Provider labels observed in the field whose public-guide names are semantically equivalent
     * but cannot be reached by punctuation/quality normalisation. Values are the guide's literal
     * display names, not ids, so this remains feed-agnostic; [matchExact] also guarantees that a
     * missing East/West target never falls through to the opposite coast.
     */
    private val PROVIDER_KEY_ALIASES: Map<String, List<String>> = mapOf(
        "ae" to listOf("A and E HD East"),
        "abceast" to listOf("ABC National Feed"),
        "abcwest" to listOf("ABC National Feed Pacific"),
        "amceast" to listOf("AMC HD"),
        "amcstoriesbyamc" to listOf("Stories by AMC"),
        "bbcworldnews" to listOf("BBC News (North America) HD"),
        "cbseast" to listOf("CBS Streaming SD East feed"),
        "cinemaxhitseast" to listOf("Cinemax Hits"),
        "cowboychannel" to listOf("The Cowboy Channel"),
        "crimeinvestigation" to listOf("Crime and Investigation Network HD"),
        "discoveryeast" to listOf("Discovery Channel HD"),
        "discoverywest" to listOf("The Discovery Channel HD (Pacific)"),
        "disneyjr" to listOf("Disney Junior HD"),
        "disneyjrwest" to listOf("Disney Junior HD (Pacific)"),
        "eentertainmenteast" to listOf("E! Entertainment Television HD"),
        "ewtn" to listOf("EWTN - Eternal Word Television Network HD"),
        "fetv" to listOf("Family Entertainment Television"),
        "fxxeast" to listOf("FXX HD"),
        "fxxwest" to listOf("FXX HD (Pacific)"),
        "gettvgreatentertainmenttelevision" to listOf("Great Entertainment Television"),
        "gacliving" to listOf("Great American Faith and Living"),
        "heroesicons" to listOf("Heroes and Icons Network SD"),
        "hgtveast" to listOf("Home and Garden Television HD"),
        "hgtvwest" to listOf("Home and Garden Television HD (Pacific)"),
        "ion" to listOf("ION Television HD"),
        "mtv" to listOf("MTV - Music Television HD"),
        "mtvwest" to listOf("MTV - Music Television HD (Pacific)"),
        "natgeowild" to listOf("National Geographic Wild HD"),
        "outsidetv" to listOf("Outside Television HD"),
        "own" to listOf("Oprah Winfrey Network HD"),
        "ownwest" to listOf("Oprah Winfrey Network (Pacific A Feed)"),
        "oxygeneast" to listOf("Oxygen True Crime HD"),
        "pbs" to listOf("PBS Stream"),
        "retroplexeast" to listOf("RetroPlex HD"),
        "sciencediscovery" to listOf("Science Channel HD"),
        "showtimeeast" to listOf("Paramount+ with Showtime HD"),
        "showtimewest" to listOf("Paramount+ with Showtime HD (Pacific)"),
        "smithsonianchanneleast" to listOf("Smithsonian HD Network"),
        "sonlifesbn" to listOf("SonLife Broadcasting Network HD"),
        "starzcinemaeast" to listOf("Starz Cinema HD"),
        "starzcomedyeast" to listOf("Starz Comedy HD"),
        "starzedgeeast" to listOf("Starz Edge HD"),
        "starzencoreeast" to listOf("Starz Encore HD"),
        "starzencorewest" to listOf("Starz Encore (Pacific)"),
        "starzencoreactioneast" to listOf("Starz Encore Action HD"),
        "starzencoreblackeast" to listOf("Starz Encore Black"),
        "starzencoreclassiceast" to listOf("Starz Encore Classic"),
        "starzencorefamilyeast" to listOf("Starz Encore Family SD"),
        "starzencoresuspenseeast" to listOf("Starz Encore Suspense"),
        "starzencorewesternseast" to listOf("Starz Encore Westerns SD"),
        "starzencorewesternswest" to listOf("Starz Encore Westerns (Pacific)"),
        "starzinblackeast" to listOf("Starz in Black HD"),
        "starzkidsfamilyeast" to listOf("Starz Kids HD"),
        "tcm" to listOf("Turner Classic Movies HD"),
        "tlceast" to listOf("TLC HD (US)"),
        "tlcwest" to listOf("TLC HD (Pacific)"),
        "traveleast" to listOf("The Travel Channel HD"),
        "travelwest" to listOf("The Travel Channel HD (Pacific)"),
        "tvlandeast" to listOf("TV Land HD"),
        "tvlandwest" to listOf("TV Land HD (Pacific)"),
        "tytnetwork" to listOf("TYT The Young Turks"),
        "usukbabytv" to listOf("Baby TV US"),
        "weatherchannel" to listOf("The Weather Channel HD"),
    )
}
