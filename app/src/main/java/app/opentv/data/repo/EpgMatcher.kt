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
 * concludes the whole guide is broken. Only literal ids, unique terminal feed revisions,
 * exact names and reviewed semantic aliases qualify. Unique prefixes are not identity.
 */
object EpgMatcher {

    /** Immutable normalization shared by scoped indexes; never re-run regexes per scope. */
    internal data class PreparedAlias(val epgId: String, val literalId: String,
        val canonicalId: String, val keys: List<String>)

    internal fun prepareAlias(epgId: String, normalizedName: String): PreparedAlias {
        val canonical = canonicalGuideIdKey(epgId)
        return PreparedAlias(epgId, epgId.trim(), canonical,
            listOf(normalizedName, ChannelNameNormalizer.normalize(epgId).groupKey, canonical).distinct())
    }

    class Index internal constructor(
        private val exact: Map<String, String>,
        private val literalIds: Map<String, String>,
        private val canonicalIds: Map<String, String>,
        /** Null is an ambiguous key, distinct from a key absent from the map. */
        private val keyIds: Map<String, String?>,
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
                ?.let(::matchId)
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

        fun matchId(id: String): String? = literalIds[id.trim()]
            ?: canonicalIds[canonicalGuideIdKey(id)]

        /** Exact-only lookup for authoritative ids and direction-sensitive aliases. */
        private fun matchExact(groupKey: String): String? = exact[groupKey]

        /** Returns the epg id for a provider channel's group key, or null. */
        fun match(groupKey: String): String? {
            if (groupKey.length < MIN_KEY_LENGTH) return null

            exact[groupKey]?.let { return it }

            // Uniqueness is not identity: ESPN11 is not ESPN1; HGTV West is not HGTV;
            // US Asian streams are not USA Network. Only exact or reviewed aliases qualify.
            return null
        }

        /** Retained diagnostic API name; only exact identity collisions are now ambiguous. */
        fun hasAmbiguousPrefix(groupKey: String): Boolean = keyIds.containsKey(groupKey) && keyIds[groupKey] == null
    }

    /**
     * Builds the lookup from every `<channel>` element seen across every enabled guide.
     *
     * Both the display-name (`BBC One`) and the raw id (`bbc1.uk`) are indexed — providers
     * that *do* fill in `epg_channel_id` often use the id form, and it costs nothing to
     * accept either.
     */
    fun buildIndex(aliases: Iterable<Pair<String, String>>, checkCancellation: () -> Unit = {}): Index {
        var visited = 0
        val prepared = aliases.map { (id, name) ->
            if (visited++ % 64 == 0) checkCancellation()
            prepareAlias(id, ChannelNameNormalizer.normalize(name).groupKey)
        }
        return buildPreparedIndex(prepared, checkCancellation)
    }

    internal fun buildPreparedIndex(aliases: Iterable<PreparedAlias>, checkCancellation: () -> Unit = {}): Index {
        val exact = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        val allKeys = HashSet<String>()
        val literalIds = HashMap<String, String>()
        val canonicalCandidates = HashMap<String, MutableSet<String>>()

        var visited = 0
        for (alias in aliases) {
            if (visited++ % 64 == 0) checkCancellation()
            val epgId = alias.epgId
            literalIds[alias.literalId] = epgId
            canonicalCandidates.getOrPut(alias.canonicalId) { HashSet() }.add(epgId)
            for (key in alias.keys) {
                // Full normalisation strips display-name quality markers; the additional
                // canonical id key tolerates only a terminal guide-feed revision.
                if (key.length < MIN_KEY_LENGTH) continue
                allKeys += key
                val existing = exact.putIfAbsent(key, epgId)
                if (existing != null && existing != epgId) {
                    // Two different guide channels normalise to the same key. Neither can
                    // be trusted for exact matching; drop the key rather than pick a side.
                    ambiguous += key
                }
            }
        }
        ambiguous.forEach(exact::remove)

        checkCancellation()
        val keyIds = allKeys.associateWith { key -> if (key in ambiguous) null else exact[key] }
        return Index(exact, literalIds, canonicalCandidates.mapNotNull { (key, ids) ->
            ids.singleOrNull()?.let { key to it }
        }.toMap(), keyIds)
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
        val withoutFeedRevision = GUIDE_ID_FEED_SUFFIX.replace(id.trim()) { ".${it.groupValues[1]}" }
        return ChannelNameNormalizer.normalize(withoutFeedRevision).groupKey
    }

    private val GUIDE_ID_FEED_SUFFIX = Regex(
        pattern = "[._-](us|ca|uk|gb|au|nz)[0-9]*$",
        option = RegexOption.IGNORE_CASE,
    )

    private val SAFE_PLATFORM_PREFIXES = listOf("samsung")

    /** Reviewed name equivalence only; never a generic prefix/word deletion. */
    internal fun reviewedIdentityKey(key: String): String = PROVIDER_KEY_ALIASES[key]?.singleOrNull()
        ?.let { ChannelNameNormalizer.normalize(it).groupKey } ?: key

    /**
     * Provider labels observed in the field whose public-guide names are semantically equivalent
     * but cannot be reached by punctuation/quality normalisation. Values are the guide's literal
     * display names, not ids, so this remains feed-agnostic; [matchExact] also guarantees that a
     * missing East/West target never falls through to the opposite coast.
     */
    private val PROVIDER_KEY_ALIASES: Map<String, List<String>> = mapOf(
        "ae" to listOf("A and E HD East"),
        "americanheroes" to listOf("American Heroes Channel HD"),
        "beteast" to listOf("BET HD"),
        "cartoonnetworkeast" to listOf("Cartoon Network HD"),
        "cheddarbusiness" to listOf("Cheddar"),
        "foodnetworkeast" to listOf("Food Network HD"),
        "foxnews" to listOf("Fox News Channel HD"),
        "freeformeast" to listOf("Freeform HD"),
        "fxeast" to listOf("FX HD"),
        "fxm" to listOf("FX Movie Channel HD"),
        "lifetimemovienetworklmn" to listOf("LMN HD"),
        "msnbcmsnow" to listOf("MS NOW HD"),
        "nbceast" to listOf("NBC East Stream"),
        "nbcwest" to listOf("NBC West Stream"),
        "shoxbeteast" to listOf("SHO x BET HD"),
        "thegrioblacknewschannel" to listOf("The Grio HD"),
        "vh1east" to listOf("VH1 HD"),
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
