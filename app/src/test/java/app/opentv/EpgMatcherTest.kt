/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.EpgMatcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The matcher's contract: prefer no match to a wrong match. A missing guide entry is an
 * annoyance with a manual override; a wrong one convinces the user the whole guide lies.
 */
class EpgMatcherTest {

    /** Shorthand: build an index from (epgId, displayName) pairs. */
    private fun index(vararg aliases: Pair<String, String>) =
        EpgMatcher.buildIndex(aliases.toList())

    private fun keyOf(providerName: String) =
        ChannelNameNormalizer.normalize(providerName).groupKey

    @Test
    fun `provider channel matches guide channel by normalised name`() {
        val idx = index("bbc1.uk" to "BBC One", "itv1.uk" to "ITV1")

        assertThat(idx.match(keyOf("UK| BBC ONE FHD"))).isEqualTo("bbc1.uk")
        assertThat(idx.match(keyOf("UK - ITV 1 HD"))).isEqualTo("itv1.uk")
    }

    @Test
    fun `the raw guide id is matchable too`() {
        // Providers that do fill in epg_channel_id often use the id form directly.
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.match(ChannelNameNormalizer.groupKeyOf("bbc1.uk"))).isEqualTo("bbc1.uk")
    }

    @Test
    fun `provider supplied guide id is tried before the display name`() {
        val idx = index(
            "ABC.National.Feed.us2" to "ABC National Feed",
            "unrelated.us2" to "Unrelated",
        )

        assertThat(
            idx.matchProvider(
                groupKey = keyOf("A provider abbreviation that cannot name-match"),
                providerEpgId = "ABC.National.Feed.us2",
            ),
        ).isEqualTo("ABC.National.Feed.us2")
    }

    @Test
    fun `provider guide id survives a terminal feed revision change`() {
        val idx = index(
            "ABC.National.Feed.us2" to "ABC National Feed",
            "ABC.National.Feed.Pacific.us2" to "ABC National Feed Pacific",
        )

        assertThat(idx.matchProvider(keyOf("unmatchable east label"), "ABC.National.Feed.us"))
            .isEqualTo("ABC.National.Feed.us2")
        assertThat(
            idx.matchProvider(
                keyOf("unmatchable west label"),
                "ABC.National.Feed.Pacific.us",
            ),
        ).isEqualTo("ABC.National.Feed.Pacific.us2")
    }

    @Test
    fun `canonical provider id never crosses direction when target is absent`() {
        val eastOnly = index("ABC.National.Feed.us2" to "ABC National Feed")

        assertThat(
            eastOnly.matchProvider(
                keyOf("unmatchable west label"),
                "ABC.National.Feed.Pacific.us",
            ),
        ).isNull()
    }

    @Test
    fun `blank provider guide id falls back to the display name`() {
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.matchProvider(keyOf("BBC ONE"), "  ")).isEqualTo("bbc1.uk")
    }

    @Test
    fun `plus network does not collide with its linear channel`() {
        val idx = index(
            "AMC.HD.us2" to "AMC HD",
            "AMC+.us2" to "AMC+",
        )

        assertThat(idx.matchProvider(keyOf("AMC"), null)).isEqualTo("AMC.HD.us2")
        assertThat(idx.matchProvider(keyOf("AMC+"), null)).isEqualTo("AMC+.us2")
    }

    @Test
    fun `observed FAST platform prefix is stripped only for an unambiguous channel`() {
        val idx = index("FOX.SOUL.us2" to "FOX SOUL")

        assertThat(idx.matchProvider(keyOf("Samsung FOX SOUL"), null))
            .isEqualTo("FOX.SOUL.us2")
    }

    @Test
    fun `FAST platform prefix refuses an ambiguous remainder`() {
        val idx = index(
            "vice.us2" to "Vice",
            "vice-hd.us2" to "Vice HD",
        )

        assertThat(idx.matchProvider(keyOf("Samsung Vice"), null)).isNull()
    }

    @Test
    fun `ABC east and west map to the correct national feeds`() {
        val idx = index(
            "ABC.National.Feed.us2" to "ABC National Feed",
            "ABC.National.Feed.Pacific.us2" to "ABC National Feed Pacific",
        )

        assertThat(idx.matchProvider(keyOf("ABC EAST"), null))
            .isEqualTo("ABC.National.Feed.us2")
        assertThat(idx.matchProvider(keyOf("ABC WEST"), null))
            .isEqualTo("ABC.National.Feed.Pacific.us2")
    }

    @Test
    fun `ABC directional aliases never cross when one feed is absent`() {
        val eastOnly = index("ABC.National.Feed.us2" to "ABC National Feed")
        val westOnly = index(
            "ABC.National.Feed.Pacific.us2" to "ABC National Feed Pacific",
        )

        assertThat(eastOnly.matchProvider(keyOf("ABC WEST"), null)).isNull()
        assertThat(westOnly.matchProvider(keyOf("ABC EAST"), null)).isNull()
    }

    @Test
    fun `observed US provider labels map to exact public guide names`() {
        val cases = linkedMapOf(
            "A&E" to "A and E HD East",
            "ABC EAST" to "ABC National Feed",
            "ABC WEST" to "ABC National Feed Pacific",
            "AMC EAST" to "AMC HD",
            "AMC STORIES BY AMC" to "Stories by AMC",
            "BBC WORLD NEWS" to "BBC News (North America) HD",
            "CBS EAST" to "CBS Streaming SD East feed",
            "CINEMAX HITS EAST" to "Cinemax Hits",
            "COWBOY CHANNEL" to "The Cowboy Channel",
            "CRIME & INVESTIGATION" to "Crime and Investigation Network HD",
            "DISCOVERY EAST" to "Discovery Channel HD",
            "DISCOVERY WEST" to "The Discovery Channel HD (Pacific)",
            "DISNEY JR" to "Disney Junior HD",
            "DISNEY JR WEST" to "Disney Junior HD (Pacific)",
            "E ENTERTAINMENT EAST" to "E! Entertainment Television HD",
            "EWTN" to "EWTN - Eternal Word Television Network HD",
            "FETV" to "Family Entertainment Television",
            "FXX EAST" to "FXX HD",
            "FXX WEST" to "FXX HD (Pacific)",
            "GET TV GREAT ENTERTAINMENT TELEVISION" to "Great Entertainment Television",
            "GAC LIVING" to "Great American Faith and Living",
            "HEROES & ICONS" to "Heroes and Icons Network SD",
            "HGTV EAST" to "Home and Garden Television HD",
            "HGTV WEST" to "Home and Garden Television HD (Pacific)",
            "ION" to "ION Television HD",
            "MTV" to "MTV - Music Television HD",
            "MTV WEST" to "MTV - Music Television HD (Pacific)",
            "NAT GEO WILD" to "National Geographic Wild HD",
            "OUTSIDE TV" to "Outside Television HD",
            "OWN" to "Oprah Winfrey Network HD",
            "OWN WEST" to "Oprah Winfrey Network (Pacific A Feed)",
            "OXYGEN EAST" to "Oxygen True Crime HD",
            "PBS" to "PBS Stream",
            "RETROPLEX EAST" to "RetroPlex HD",
            "SCIENCE DISCOVERY" to "Science Channel HD",
            "SHOWTIME EAST" to "Paramount+ with Showtime HD",
            "SHOWTIME WEST" to "Paramount+ with Showtime HD (Pacific)",
            "SMITHSONIAN CHANNEL EAST" to "Smithsonian HD Network",
            "SONLIFE SBN" to "SonLife Broadcasting Network HD",
            "STARZ CINEMA EAST" to "Starz Cinema HD",
            "STARZ COMEDY EAST" to "Starz Comedy HD",
            "STARZ EDGE EAST" to "Starz Edge HD",
            "STARZ ENCORE EAST" to "Starz Encore HD",
            "STARZ ENCORE WEST" to "Starz Encore (Pacific)",
            "STARZ ENCORE ACTION EAST" to "Starz Encore Action HD",
            "STARZ ENCORE BLACK EAST" to "Starz Encore Black",
            "STARZ ENCORE CLASSIC EAST" to "Starz Encore Classic",
            "STARZ ENCORE FAMILY EAST" to "Starz Encore Family SD",
            "STARZ ENCORE SUSPENSE EAST" to "Starz Encore Suspense",
            "STARZ ENCORE WESTERNS EAST" to "Starz Encore Westerns SD",
            "STARZ ENCORE WESTERNS WEST" to "Starz Encore Westerns (Pacific)",
            "STARZ IN BLACK EAST" to "Starz in Black HD",
            "STARZ KIDS & FAMILY EAST" to "Starz Kids HD",
            "TCM" to "Turner Classic Movies HD",
            "TLC EAST" to "TLC HD (US)",
            "TLC WEST" to "TLC HD (Pacific)",
            "TRAVEL EAST" to "The Travel Channel HD",
            "TRAVEL WEST" to "The Travel Channel HD (Pacific)",
            "TV LAND EAST" to "TV Land HD",
            "TV LAND WEST" to "TV Land HD (Pacific)",
            "TYT NETWORK" to "TYT The Young Turks",
            "US UK BABY TV" to "Baby TV US",
            "WEATHER CHANNEL" to "The Weather Channel HD",
        )
        val guideIds = cases.values.distinct().mapIndexed { index, name ->
            name to "guide-$index"
        }.toMap()
        val idx = EpgMatcher.buildIndex(guideIds.map { (name, id) -> id to name })

        for ((providerName, guideName) in cases) {
            assertThat(idx.matchProvider(keyOf(providerName), null))
                .isEqualTo(guideIds.getValue(guideName))
        }
    }

    @Test
    fun `unique prefix matches are accepted`() {
        val idx = index("bbc1london.uk" to "BBC One London")

        // Provider says just "BBC One"; the only candidate is the London region — take it.
        assertThat(idx.match(keyOf("UK| BBC ONE"))).isEqualTo("bbc1london.uk")
    }

    @Test
    fun `ambiguous prefix matches are refused`() {
        val idx = index(
            "bbc1london.uk" to "BBC One London",
            "bbc1wales.uk" to "BBC One Wales",
        )

        // Two regions fit and nothing exact does. Guessing here shows the wrong regional
        // news against the channel — refuse, and let the manual override decide.
        assertThat(idx.match(keyOf("UK| BBC ONE"))).isNull()
    }

    @Test
    fun `colliding display names disable exact matching for that key`() {
        val idx = index(
            "cnn.us" to "CNN",
            "cnn.int" to "CNN",
        )

        assertThat(idx.match(keyOf("CNN"))).isNull()
    }

    @Test
    fun `unknown channels match nothing`() {
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.match(keyOf("UK| SOME SHOP CHANNEL"))).isNull()
    }

    @Test
    fun `keys too short to mean anything never match`() {
        val idx = index("e.uk" to "E")

        assertThat(idx.match("e")).isNull()
        // But two characters is a real channel name in Britain.
        val e4 = index("e4.uk" to "E4")
        assertThat(e4.match(keyOf("UK| E4 HD"))).isEqualTo("e4.uk")
    }

    @Test
    fun `end to end - the exact names from the reporting provider`() {
        // The names that started all this, verbatim from the screen.
        val idx = index(
            "bbc1.uk" to "BBC One",
            "bbc2.uk" to "BBC Two",
            "channel4.uk" to "Channel 4",
            "skyatlantic.uk" to "Sky Atlantic",
        )

        assertThat(idx.match(keyOf("UK| BBC ONE HD/RAW"))).isEqualTo("bbc1.uk")
        assertThat(idx.match(keyOf("UK| BBC TWO FHD"))).isEqualTo("bbc2.uk")
        assertThat(idx.match(keyOf("UK| CHANNEL 4 hevc"))).isEqualTo("channel4.uk")
        assertThat(idx.match(keyOf("UK| SKY ATLANTIC ᴴᴰ"))).isEqualTo("skyatlantic.uk")
    }
}
