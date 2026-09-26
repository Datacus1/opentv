/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.channels.guideProgrammeLabel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
    fun `every identifiable matchup in the current public guide uses the correct teams`() {
        val cases = listOf(
            "The Mercer Bears (2-1) visit the Georgia Tech Yellow Jackets (0-2) at Bobby Dodd Stadium." to "MER vs GT",
            "Coastal Carolina hosts Liberty at Brooks Stadium in Conway." to "CCU vs LIB",
            "The Clemson Tigers host the North Carolina Tar Heels at Memorial Stadium in an ACC game." to "CLEM vs UNC",
            "The Army Black Knights (1-1) travel to face the Temple Owls (1-2) at Lincoln Financial Field." to "ARMY vs TEM",
            "The Rutgers Scarlet Knights host the Howard Bison." to "RUTG vs HOW",
            "The Navy Midshipmen (1-1) play against the UAB Blazers (1-2) at Protective Stadium." to "NAVY vs UAB",
            "The Kentucky Wildcats host the South Alabama Jaguars at Kroger Field." to "UK vs USA",
            "The No. 16 Louisville Cardinals host the Wake Forest Demon Deacons." to "#16 LOU vs WAKE",
            "The No. 11 Texas Tech Red Raiders host the Sam Houston Bearkats at Galaxy Stadium." to "#11 TTU vs SHSU",
            "The Auburn Tigers (2-1) host the unbeaten Vanderbilt Commodores (3-0) in an SEC matchup." to "AUB vs VAN",
            "The Florida State Seminoles host Central Arkansas." to "FSU vs UCA",
            "Wyoming hosts Hawaii at War Memorial Stadium in Mountain West Week 4 college football play." to "WYO vs HAW",
            (
                "The Aggies host the Lobos at Aggie Memorial Stadium. " +
                    "The Aggies fell to the Rainbow Warriors, and the Lobos lost to the Sooners."
                ) to "NMSU vs UNM",
            "Michigan hosts Big Ten rival Iowa at Ann Arbor in Week 4 college football action." to "MICH vs IOWA",
            "No.2 Georgia Bulldogs host the Oklahoma Sooners at Sanford Stadium in SEC regular-season game." to "#2 UGA vs OU",
            "The Western Michigan Bronchos (2-1) host the Boise State Bronchos (2-1) at Waldo Stadium." to "WMU vs BSU",
            "No.24 Mississippi State hosts No.19 Missouri at Davis Wade Stadium in SEC Week 4 play." to "#24 MSST vs #19 MIZ",
            "The Michigan State Spartans host the Nebraska Cornhuskers." to "MSU vs NEB",
            "The Hurricanes (3-0) host the Chippewas (2-1) at Hard Rock Stadium." to "MIA vs CMU",
            "No.8 Alabama Crimson Tide host the South Carolina Gamecocks in SEC regular-season game." to "#8 ALA vs SC",
            "The Cincinnati Bearcats host the Kansas State Wildcats in a Big 12 conference game." to "CIN vs KSU",
            "The West Virginia host Oklahoma State." to "WVU vs OKST",
            "The No. 10 LSU Tigers host the No. 23 Texas A&M Aggies at Tiger Stadium." to "#10 LSU vs #23 TAMU",
            "The Utah State Aggies host the Troy Trojans at Maverik Stadium." to "USU vs TROY",
            "Washington State welcomes Arizona to Pullman as both teams collide in Week 4 game." to "WSU vs ARIZ",
            "USC hosts Big Ten rival Oregon at LA Memorial Coliseum in Week 4 college football action." to "USC vs ORE",
            (
                "The Bears (1-2) visit the No. 22 Mustangs (2-1) at Gerald J. Ford Stadium. " +
                    "SMU seeks to rebound, while Missouri State fell in its last game."
                ) to "MOST vs #22 SMU",
            (
                "The Bulldogs host the Owls at the Valley Children's Stadium. " +
                    "Fresno State is led by Jayden Mandal, while Jacurri Brown anchors the Owls."
                ) to "FRES vs RICE",
            "The Yellow Jackets (1-2) visit the Cardinal (1-2) at Stanford Stadium." to "GT vs STAN",
            "Montana State hosts Northern Arizona at Bobcat Stadium in Big Sky Week 4 college football game." to "MTST vs NAU",
            "The Nevada Wolf Pack host the Air Force Falcons at Mackay Stadium." to "NEV vs AFA",
            (
                "The Tigers (2-1) travel to face the Golden Bears (2-1) at California Memorial Stadium. " +
                    "The Tigers are led by Tait Reynolds."
                ) to "CLEM vs CAL",
            "Iowa State hosts the No.15 Utah Utes at Jack Trice Stadium." to "ISU vs #15 UTAH",
            "No. 14 Tennessee hosts No. 1 Texas at Neyland Stadium." to "#14 TENN vs #1 TEX",
        )

        for ((description, expected) in cases) {
            assertWithMessage(description)
                .that(guideProgrammeLabel("College Football", description).matchup)
                .isEqualTo(expected)
        }
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
