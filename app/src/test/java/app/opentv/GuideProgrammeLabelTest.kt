/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.channels.guideProgrammeLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuideProgrammeLabelTest {

    @Test
    fun `ranked matchup keeps college football heading and adds compact teams`() {
        val label = guideProgrammeLabel(
            "College Football",
            "The No. 7 Ohio State Buckeyes host the Illinois Fighting Illini at Ohio Stadium.",
        )

        assertThat(label.timelineText).isEqualTo("College Football\n#7 OSU vs ILL")
        assertThat(label.singleLineText).isEqualTo("College Football · #7 OSU vs ILL")
    }

    @Test
    fun `ranking period is not mistaken for the end of the visiting team`() {
        val label = guideProgrammeLabel(
            "College Football",
            "The No. 4 Rebels (3-0) visit the No. 21 Gators (3-0) at Ben Hill Griffin Stadium. " +
                "Ole Miss enters Week 4 after a win, while Florida remains unbeaten.",
        )

        assertThat(label.timelineText)
            .isEqualTo("College Football\n#4 OLE MISS vs #21 FLA")
        assertThat(label.singleLineText)
            .isEqualTo("College Football · #4 OLE MISS vs #21 FLA")
    }

    @Test
    fun `live prefix is normalized while matchup remains`() {
        val label = guideProgrammeLabel(
            "Live: College Football",
            "The UCF Knights host the TCU Horned Frogs in Big 12 Conference regular-season play.",
        )

        assertThat(label.timelineText).isEqualTo("College Football\nUCF vs TCU")
    }

    @Test
    fun `shared mascot is resolved by school named later in synopsis`() {
        val label = guideProgrammeLabel(
            "College Football",
            "The No. 5 Hoosiers face the Wildcats to kick off Big Ten conference play. " +
                "Indiana won last week. Northwestern defeated Colorado.",
        )

        assertThat(label.timelineText).isEqualTo("College Football\n#5 IND vs NW")
    }

    @Test
    fun `huskies are resolved from later Washington reference`() {
        val label = guideProgrammeLabel(
            "College Football",
            "The Huskies host the Golden Gophers at Husky Stadium. " +
                "Washington seeks a home win, while Minnesota builds momentum.",
        )

        assertThat(label.timelineText).isEqualTo("College Football\nWASH vs MINN")
    }

    @Test
    fun `face off wording produces matchup`() {
        val label = guideProgrammeLabel(
            "College Football",
            "Colorado and Baylor face off in the Big 12 opener.",
        )

        assertThat(label.timelineText).isEqualTo("College Football\nCOLO vs BAY")
    }

    @Test
    fun `venue then take on wording produces compact fallback`() {
        val label = guideProgrammeLabel(
            "College Football",
            "The Rams travel to the Alamodome to take on the Roadrunners in nonconference play.",
        )

        assertThat(label.timelineText).isEqualTo("College Football\nCSU vs UTSA")
    }

    @Test
    fun `conference qualifier is removed from opponent`() {
        val label = guideProgrammeLabel(
            "College Football",
            "Maryland hosts Big Ten opponent UCLA at SECU Stadium in Week 4.",
        )

        assertThat(label.timelineText).isEqualTo("College Football\nMD vs UCLA")
    }

    @Test
    fun `vague sports description stays unchanged`() {
        val label = guideProgrammeLabel("College Football", "All the action from college football.")

        assertThat(label.timelineText).isEqualTo("College Football")
        assertThat(label.matchup).isNull()
    }

    @Test
    fun `podcast title is never rewritten`() {
        val title = "The Joel Klatt Show: A College Football Podcast"
        val label = guideProgrammeLabel(title, "Ohio State hosts Illinois.")

        assertThat(label.timelineText).isEqualTo(title)
        assertThat(label.matchup).isNull()
    }
}
