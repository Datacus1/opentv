/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

/** Text variants for a programme in the timeline and the larger guide preview. */
internal data class GuideProgrammeLabel(
    val heading: String,
    val matchup: String? = null,
) {
    val timelineText: String get() = matchup?.let { "$heading\n$it" } ?: heading
    val singleLineText: String get() = matchup?.let { "$heading · $it" } ?: heading
}

/**
 * EPGShare frequently labels every game simply "College Football" and puts the matchup in the
 * description. Keep that familiar heading, but recover a compact second line for the TV guide.
 * Anything that cannot be identified conservatively retains the provider's original title.
 */
internal fun guideProgrammeLabel(title: String, description: String?): GuideProgrammeLabel {
    if (!GENERIC_COLLEGE_FOOTBALL.matches(title.trim())) return GuideProgrammeLabel(title)
    val text = description?.trim().orEmpty()
    val rawTeams = extractMatchup(text) ?: return GuideProgrammeLabel(COLLEGE_FOOTBALL)
    val first = compactTeam(rawTeams.first, text)
    val second = compactTeam(rawTeams.second, text)
    if (first == null || second == null || first == second) return GuideProgrammeLabel(COLLEGE_FOOTBALL)
    return GuideProgrammeLabel(COLLEGE_FOOTBALL, "$first vs $second")
}

private fun extractMatchup(description: String): Pair<String, String>? {
    if (description.isBlank()) return null
    // A period normally terminates the first matchup sentence, but the ranking abbreviation
    // `No.` is not a sentence boundary. Normalise only `No.` immediately before a rank so a
    // visitor such as "No. 21 Gators" is not truncated to the bogus fallback team `NO`.
    val text = RANK_PERIOD.replace(description, "No ")
    FACE_OFF.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
    TRAVEL_TO_TAKE_ON.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
    MATCHUP_VERB.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
    SIMPLE_VERSUS.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
    return null
}

private fun compactTeam(raw: String, description: String): String? {
    val rank = RANK.find(raw)?.groupValues?.get(1)
    val cleaned = raw
        .replace(RANK, " ")
        .replace(RECORD, " ")
        .replace(LEADING_QUALIFIER, "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', ',', '-', ':')
    if (cleaned.isBlank()) return null

    val direct = TEAMS
        .filter { team -> team.names.any { cleaned.containsPhrase(it) } }
        .maxByOrNull { team -> team.names.maxOf { it.length } }

    val byMascot = if (direct == null) {
        val mascotCandidates = TEAMS.filter { team ->
            team.mascots.any { mascot -> cleaned.equals(mascot, ignoreCase = true) }
        }
        when {
            mascotCandidates.size == 1 -> mascotCandidates.single()
            mascotCandidates.size > 1 -> mascotCandidates
                .filter { team -> team.names.any { description.containsPhrase(it) } }
                .singleOrNull()
            else -> null
        }
    } else {
        null
    }

    val abbreviation = direct?.abbreviation ?: byMascot?.abbreviation ?: fallbackAbbreviation(cleaned)
    if (abbreviation.isBlank()) return null
    return if (rank != null) "#$rank $abbreviation" else abbreviation
}

private fun fallbackAbbreviation(team: String): String {
    ACRONYM.find(team)?.value?.let { return it.uppercase() }
    val words = team.split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() && it.lowercase() !in STOP_WORDS }
    if (words.isEmpty()) return ""
    if (words.size == 1) return words.single().uppercase().take(4)
    return words.take(4).joinToString("") { it.first().uppercase() }
}

private fun String.containsPhrase(phrase: String): Boolean =
    Regex("(?i)(?<![A-Za-z0-9])${Regex.escape(phrase)}(?![A-Za-z0-9])").containsMatchIn(this)

private data class TeamIdentity(
    val abbreviation: String,
    val names: List<String>,
    val mascots: List<String> = emptyList(),
)

private fun team(abbreviation: String, vararg names: String, mascots: List<String> = emptyList()) =
    TeamIdentity(abbreviation, names.toList(), mascots)

private const val COLLEGE_FOOTBALL = "College Football"
private val GENERIC_COLLEGE_FOOTBALL =
    Regex("^(?:live:\\s*)?(?:ncaa\\s+)?college football$", RegexOption.IGNORE_CASE)
private val RANK = Regex("(?i)\\bNo\\.?\\s*(\\d{1,2})\\b")
private val RANK_PERIOD = Regex("(?i)\\bNo\\.\\s*(?=\\d{1,2}\\b)")
private val RECORD = Regex("\\(\\s*\\d+\\s*-\\s*\\d+\\s*\\)")
private val LEADING_QUALIFIER = Regex(
    "(?i)^(?:the\\s+)?(?:(?:Big Ten|Big 12|ACC|SEC|AAC|SIAC|Ivy League|Mountain West)\\s+)?" +
        "(?:(?:conference\\s+)?(?:opponent|rival)\\s+|unbeaten\\s+)?(?:the\\s+)?",
)
private val ACRONYM = Regex("(?<![A-Za-z0-9])[A-Z][A-Z0-9&]{1,5}(?![A-Za-z0-9])")
private val STOP_WORDS = setOf("the", "of", "and", "at")

private const val TEAM_END =
    "(?=\\s+(?:at|in|for|to kick|during|as both|on)\\b|[.;]|$)"
private val FACE_OFF = Regex(
    "^\\s*(?:the\\s+)?(.+?)\\s+and\\s+(.+?)\\s+face off\\b",
    RegexOption.IGNORE_CASE,
)
private val TRAVEL_TO_TAKE_ON = Regex(
    "^\\s*(?:the\\s+)?(.+?)\\s+travel(?:s)?\\s+to\\b.*?\\s+to take on\\s+(?:the\\s+)?(.+?)$TEAM_END",
    RegexOption.IGNORE_CASE,
)
private val MATCHUP_VERB = Regex(
    "^\\s*(?:the\\s+)?(.+?)\\s+" +
        "(?:host(?:s)?|face(?:s)?|visit(?:s)?|welcome(?:s)?|play(?:s)?(?:\\s+against)?|" +
        "travel(?:s)?\\s+to\\s+face|take(?:s)?\\s+on)\\s+(?:the\\s+)?(.+?)$TEAM_END",
    RegexOption.IGNORE_CASE,
)
private val SIMPLE_VERSUS = Regex(
    "^\\s*(?:the\\s+)?(.+?)\\s+(?:versus|vs\\.?|at)\\s+(?:the\\s+)?(.+?)(?=[.;]|$)",
    RegexOption.IGNORE_CASE,
)

/**
 * Explicit school names handle the normal case. Mascots are used only when unique, or when a
 * school name elsewhere in the same synopsis disambiguates shared mascots such as Wildcats.
 */
private val TEAMS = listOf(
    team("AFA", "Air Force", mascots = listOf("Falcons")),
    team("ALA", "Alabama", mascots = listOf("Crimson Tide")),
    team("APP", "Appalachian State", "App State", mascots = listOf("Mountaineers")),
    team("ARIZ", "Arizona", mascots = listOf("Wildcats")),
    team("ARMY", "Army", mascots = listOf("Black Knights")),
    team("AUB", "Auburn", mascots = listOf("Tigers")),
    team("BAY", "Baylor", mascots = listOf("Bears")),
    team("BC", "Boston College", mascots = listOf("Eagles")),
    team("BEN", "Benedict College", "Benedict", mascots = listOf("Tigers")),
    team("BSU", "Boise State", mascots = listOf("Broncos", "Bronchos")),
    team("BRWN", "Brown", mascots = listOf("Bears")),
    team("CAL", "California", "Cal", mascots = listOf("Golden Bears")),
    team("CIN", "Cincinnati", mascots = listOf("Bearcats")),
    team("CLEM", "Clemson", mascots = listOf("Tigers")),
    team("CMU", "Central Michigan", mascots = listOf("Chippewas")),
    team("COLO", "Colorado", mascots = listOf("Buffaloes")),
    team("CSU", "Colorado State", mascots = listOf("Rams")),
    team("DEL", "Delaware", mascots = listOf("Blue Hens")),
    team("FAU", "Florida Atlantic", "FAU", mascots = listOf("Owls")),
    team("FLA", "Florida", mascots = listOf("Gators")),
    team("FRES", "Fresno State", mascots = listOf("Bulldogs")),
    team("FSU", "Florida State", mascots = listOf("Seminoles")),
    team("GASO", "Georgia Southern", mascots = listOf("Eagles")),
    team("GT", "Georgia Tech", mascots = listOf("Yellow Jackets")),
    team("UGA", "Georgia", mascots = listOf("Bulldogs")),
    team("HARV", "Harvard", mascots = listOf("Crimson")),
    team("HAW", "Hawaii", mascots = listOf("Rainbow Warriors")),
    team("HOU", "Houston", mascots = listOf("Cougars")),
    team("HOW", "Howard", mascots = listOf("Bison")),
    team("ILL", "Illinois", mascots = listOf("Fighting Illini")),
    team("IND", "Indiana", mascots = listOf("Hoosiers")),
    team("IOWA", "Iowa", mascots = listOf("Hawkeyes")),
    team("ISU", "Iowa State", mascots = listOf("Cyclones")),
    team("KSU", "Kansas State", mascots = listOf("Wildcats")),
    team("UK", "Kentucky", mascots = listOf("Wildcats")),
    team("LIB", "Liberty", mascots = listOf("Flames")),
    team("LOU", "Louisville", mascots = listOf("Cardinals")),
    team("LSU", "LSU", "Louisiana State", mascots = listOf("Tigers")),
    team("MD", "Maryland", mascots = listOf("Terrapins")),
    team("MIA", "Miami", mascots = listOf("Hurricanes")),
    team("MICH", "Michigan", mascots = listOf("Wolverines")),
    team("MINN", "Minnesota", mascots = listOf("Golden Gophers")),
    team("OLE MISS", "Ole Miss", mascots = listOf("Rebels")),
    team("MIZ", "Missouri", mascots = listOf("Tigers")),
    team("MOST", "Missouri State", mascots = listOf("Bears")),
    team("MSST", "Mississippi State", mascots = listOf("Bulldogs")),
    team("MSU", "Michigan State", mascots = listOf("Spartans")),
    team("MTST", "Montana State", mascots = listOf("Bobcats")),
    team("NAU", "Northern Arizona", mascots = listOf("Lumberjacks")),
    team("NAVY", "Navy", mascots = listOf("Midshipmen")),
    team("NCSU", "NC State", "North Carolina State", mascots = listOf("Wolfpack")),
    team("NEB", "Nebraska", mascots = listOf("Cornhuskers")),
    team("NEV", "Nevada", mascots = listOf("Wolf Pack")),
    team("NMSU", "New Mexico State", mascots = listOf("Aggies")),
    team("NW", "Northwestern", mascots = listOf("Wildcats")),
    team("OSU", "Ohio State", mascots = listOf("Buckeyes")),
    team("OKST", "Oklahoma State", mascots = listOf("Cowboys")),
    team("OU", "Oklahoma", mascots = listOf("Sooners")),
    team("ORE", "Oregon", mascots = listOf("Ducks")),
    team("RICE", "Rice", mascots = listOf("Owls")),
    team("RUTG", "Rutgers", mascots = listOf("Scarlet Knights")),
    team("SC", "South Carolina", mascots = listOf("Gamecocks")),
    team("SDSU", "San Diego State", mascots = listOf("Aztecs")),
    team("SHSU", "Sam Houston", "Sam Houston State", mascots = listOf("Bearkats")),
    team("SMU", "SMU", "Southern Methodist", mascots = listOf("Mustangs")),
    team("STAN", "Stanford", mascots = listOf("Cardinal")),
    team("TAMU", "Texas A&M", mascots = listOf("Aggies")),
    team("TCU", "TCU", mascots = listOf("Horned Frogs")),
    team("TENN", "Tennessee", mascots = listOf("Volunteers")),
    team("TEM", "Temple", mascots = listOf("Owls")),
    team("TEX", "Texas", mascots = listOf("Longhorns")),
    team("TOL", "Toledo", mascots = listOf("Rockets")),
    team("TROY", "Troy", mascots = listOf("Trojans")),
    team("TTU", "Texas Tech", mascots = listOf("Red Raiders")),
    team("TUSK", "Tuskegee", mascots = listOf("Golden Tigers")),
    team("UAB", "UAB", mascots = listOf("Blazers")),
    team("UCA", "Central Arkansas", mascots = listOf("Bears")),
    team("UCF", "UCF", "Central Florida", mascots = listOf("Knights")),
    team("UCLA", "UCLA", mascots = listOf("Bruins")),
    team("UCONN", "Connecticut", "UConn", mascots = listOf("Huskies")),
    team("UNC", "North Carolina", mascots = listOf("Tar Heels")),
    team("UNLV", "UNLV", mascots = listOf("Rebels")),
    team("UNM", "New Mexico", mascots = listOf("Lobos")),
    team("USA", "South Alabama", mascots = listOf("Jaguars")),
    team("USC", "USC", "Southern California", mascots = listOf("Trojans")),
    team("USU", "Utah State", mascots = listOf("Aggies")),
    team("UTAH", "Utah", mascots = listOf("Utes")),
    team("UTSA", "UTSA", "Texas San Antonio", mascots = listOf("Roadrunners")),
    team("UVA", "Virginia", mascots = listOf("Cavaliers")),
    team("VAN", "Vanderbilt", mascots = listOf("Commodores")),
    team("VT", "Virginia Tech", mascots = listOf("Hokies")),
    team("WAKE", "Wake Forest", mascots = listOf("Demon Deacons")),
    team("WASH", "Washington", mascots = listOf("Huskies")),
    team("WMU", "Western Michigan", mascots = listOf("Broncos", "Bronchos")),
    team("WSU", "Washington State", mascots = listOf("Cougars")),
    team("WVU", "West Virginia", mascots = listOf("Mountaineers")),
    team("WYO", "Wyoming", mascots = listOf("Cowboys")),
)
