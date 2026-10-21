package dev.vr.panes

/**
 * A place to center on. [country], [state] and [region] only group the location picker; a detected place leaves them
 * empty. [state] is whatever a country divides into first (state, province, prefecture).
 */
data class Place(val name: String, val lat: Double, val lon: Double, val country: String = "", val state: String = "", val region: String = "") {
    /** The picker's top level. A country missing from [CONTINENTS] stands on its own there rather than vanish. */
    val continent get() = CONTINENTS[country] ?: country
}

/** Continents in the picker's order, after the viewer's own. */
val CONTINENT_ORDER = listOf("North America", "Europe", "Asia", "Oceania", "South America", "Africa")

private val CONTINENTS = mapOf(
    "United States" to "North America", "Canada" to "North America", "Mexico" to "North America",
    "France" to "Europe", "Italy" to "Europe", "United Kingdom" to "Europe", "Spain" to "Europe", "Germany" to "Europe",
    "Netherlands" to "Europe", "Switzerland" to "Europe", "Norway" to "Europe", "Iceland" to "Europe",
    "Poland" to "Europe", "Ukraine" to "Europe", "Türkiye" to "Europe",
    "Japan" to "Asia", "Singapore" to "Asia", "South Korea" to "Asia", "Thailand" to "Asia", "Hong Kong" to "Asia",
    "Australia" to "Oceania", "New Zealand" to "Oceania",
    "Brazil" to "South America", "Argentina" to "South America", "Chile" to "South America",
    "South Africa" to "Africa",
)

/**
 * One view. Road cams come from FL511 and are judged by their stills. Scenic cams come from the Panes server
 * and play either as HLS ([video], no auth) or as YouTube live ([tube] is the video id, or a channel id for its current live stream).
 */
data class Cam(val id: String, val lat: Double, val lon: Double, val km: Double, var name: String = "",
               var video: String = "", var videoAuth: Boolean = true,
               val tube: String = "", val still: String = "", val credit: String = "FL511, Florida DOT") {
    val title get() = name.ifEmpty { "Camera $id" }
    val miles get() = km * 0.621371
    val road get() = ':' !in id
    /** How much of the frame's top [Pane] cuts: FDOT's caption bar on road cams, nothing on scenic ones. */
    val trim get() = if (road) Pane.TRIM else 0f
    var height = 0
    /** Only ever learned from the stream itself; 0 means assume 16:9 of [height]. */
    var width = 0
    val quality get() = when { height >= 2160 -> "4K"; height >= 720 -> "HD"; height > 0 -> "SD"; else -> "" }
}

/** Places with cameras worth watching, in picker order: the United States first, then other countries A to Z. */
val CITIES = listOf(
    Place("Lake Tahoe", 39.0968, -120.0324, "United States", "California"),
    Place("Los Angeles", 34.0522, -118.2437, "United States", "California"),
    Place("Mammoth Lakes", 37.6485, -118.9721, "United States", "California"),
    Place("Monterey", 36.6002, -121.8947, "United States", "California"),
    Place("San Diego", 32.7157, -117.1611, "United States", "California"),
    Place("San Francisco", 37.7749, -122.4194, "United States", "California"),
    Place("Yosemite", 37.7456, -119.5936, "United States", "California"),
    Place("Denver", 39.7392, -104.9903, "United States", "Colorado"),
    Place("Washington", 38.9072, -77.0369, "United States", "District of Columbia"),
    Place("West Palm Beach", 26.7153, -80.0534, "United States", "Florida", "South Florida"),
    Place("Jupiter", 26.9342, -80.0942, "United States", "Florida", "South Florida"),
    Place("Boca Raton", 26.3683, -80.1289, "United States", "Florida", "South Florida"),
    Place("Fort Lauderdale", 26.1224, -80.1373, "United States", "Florida", "South Florida"),
    Place("Miami", 25.7617, -80.1918, "United States", "Florida", "South Florida"),
    Place("Key West", 24.5551, -81.7800, "United States", "Florida", "South Florida"),
    Place("Marathon", 24.7136, -81.0904, "United States", "Florida", "South Florida"),
    Place("Port St. Lucie", 27.2730, -80.3582, "United States", "Florida", "Central Florida"),
    Place("Orlando", 28.5383, -81.3792, "United States", "Florida", "Central Florida"),
    Place("Daytona Beach", 29.2108, -81.0228, "United States", "Florida", "Central Florida"),
    Place("Cape Canaveral", 28.3922, -80.6077, "United States", "Florida", "Central Florida"),
    Place("Tampa", 27.9506, -82.4572, "United States", "Florida", "Tampa Bay & Southwest"),
    Place("St. Petersburg", 27.7676, -82.6403, "United States", "Florida", "Tampa Bay & Southwest"),
    Place("Clearwater", 27.9659, -82.8001, "United States", "Florida", "Tampa Bay & Southwest"),
    Place("Anna Maria Island", 27.5300, -82.7334, "United States", "Florida", "Tampa Bay & Southwest"),
    Place("Sarasota", 27.3364, -82.5307, "United States", "Florida", "Tampa Bay & Southwest"),
    Place("Fort Myers", 26.6406, -81.8723, "United States", "Florida", "Tampa Bay & Southwest"),
    Place("Naples", 26.1420, -81.7948, "United States", "Florida", "Tampa Bay & Southwest"),
    Place("Jacksonville", 30.3322, -81.6557, "United States", "Florida", "North Florida"),
    Place("Gainesville", 29.6516, -82.3248, "United States", "Florida", "North Florida"),
    Place("Tallahassee", 30.4383, -84.2807, "United States", "Florida", "North Florida"),
    Place("Pensacola", 30.4213, -87.2169, "United States", "Florida", "North Florida"),
    Place("Honolulu", 21.3069, -157.8583, "United States", "Hawaii"),
    Place("Maui", 20.9000, -156.6800, "United States", "Hawaii"),
    Place("Kauai", 21.9000, -159.5000, "United States", "Hawaii"),
    Place("Chicago", 41.8781, -87.6298, "United States", "Illinois"),
    Place("New Orleans", 29.9511, -90.0715, "United States", "Louisiana"),
    Place("Baltimore", 39.2904, -76.6122, "United States", "Maryland"),
    Place("Boston", 42.3601, -71.0589, "United States", "Massachusetts"),
    Place("Detroit", 42.3314, -83.0458, "United States", "Michigan"),
    Place("Las Vegas", 36.1699, -115.1398, "United States", "Nevada"),
    Place("New York", 40.7128, -74.0060, "United States", "New York"),
    Place("Philadelphia", 39.9526, -75.1652, "United States", "Pennsylvania"),
    Place("Nashville", 36.1627, -86.7816, "United States", "Tennessee"),
    Place("Austin", 30.2672, -97.7431, "United States", "Texas"),
    Place("Houston", 29.7604, -95.3698, "United States", "Texas"),
    Place("Long Beach Peninsula", 46.3523, -124.0543, "United States", "Washington"),
    Place("San Juan Islands", 48.5343, -123.0171, "United States", "Washington"),
    Place("Seattle", 47.6062, -122.3321, "United States", "Washington"),
    Place("Sequim", 48.0795, -123.1018, "United States", "Washington"),
    Place("Jackson Hole", 43.4799, -110.7624, "United States", "Wyoming"),
    Place("Sydney", -33.8688, 151.2093, "Australia", "New South Wales"),
    Place("Toronto", 43.6532, -79.3832, "Canada", "Ontario"),
    Place("Vancouver", 49.2827, -123.1207, "Canada", "British Columbia"),
    Place("Paris", 48.8566, 2.3522, "France", "Île-de-France"),
    Place("Hong Kong", 22.2855, 114.1577, "Hong Kong"),
    Place("Rome", 41.9028, 12.4964, "Italy", "Lazio"),
    Place("Venice", 45.4408, 12.3155, "Italy", "Veneto"),
    Place("Tokyo", 35.6762, 139.6503, "Japan", "Tokyo"),
    Place("Kyoto", 35.0116, 135.7681, "Japan", "Kyoto"),
    Place("Mount Fuji", 35.1610, 138.6760, "Japan", "Shizuoka"),
    Place("Warsaw", 52.2297, 21.0122, "Poland", "Masovia"),
    Place("Rewal", 54.0832, 14.9990, "Poland", "West Pomerania"),
    Place("Singapore", 1.3521, 103.8198, "Singapore"),
    Place("Cape Town", -33.9249, 18.4241, "South Africa", "Western Cape"),
    Place("Seoul", 37.5665, 126.9780, "South Korea", "Seoul"),
    Place("Istanbul", 41.0082, 28.9784, "Türkiye", "Istanbul"),
    Place("London", 51.5074, -0.1278, "United Kingdom", "England"),
)

/**
 * How well [q] finds this place, 0 for not at all. Every word of the query has to land on a word of the place (its name,
 * region, state, country or continent) as the whole word, a prefix, a run of its letters or within a typo or two; "sna fran",
 * "sf" and "montrey" all find what they mean. Words of the name count for more than where it is.
 */
fun Place.match(q: String): Int {
    val terms = fold(q).split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return 0
    val own = fold(name).split(' ').filter { it.isNotEmpty() }
    val all = own + fold("$region $state $country $continent").split(' ').filter { it.isNotEmpty() }
    // Initials: "sf", "la", "nyc".
    if (terms.size == 1 && terms[0].length >= 2 && terms[0] == own.joinToString("") { it.take(1) }) return 20
    return terms.sumOf { t -> all.maxOfOrNull { w -> wordScore(t, w).let { if (it > 0 && w in own) it + 2 else it } }.let { it ?: 0 }.also { if (it == 0) return 0 } }
}

private val MARKS = Regex("\\p{M}+")
private val NOT_WORD = Regex("[^a-z0-9]+")

/** Lowercase, accents off, punctuation to spaces. */
private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    .replace(MARKS, "").lowercase().replace(NOT_WORD, " ").trim()

private fun wordScore(t: String, w: String): Int {
    val slack = when { t.length >= 7 -> 2; t.length >= 3 -> 1; else -> 0 }
    return when {
        w == t -> 10
        w.startsWith(t) -> 8
        t.length >= 3 && t in w -> 6
        slack > 0 && (t.length - 1..t.length + 1).any { it <= w.length && typos(t, w.take(it)) <= slack } -> 4
        t.length >= 3 && t[0] == w[0] && inOrder(t, w) -> 3
        else -> 0
    }
}

/** Whether [t]'s letters all appear in [w], in order: "mtry" in "monterey". */
private fun inOrder(t: String, w: String): Boolean { var i = 0; for (c in w) if (i < t.length && t[i] == c) i++; return i == t.length }

/** Edits between [a] and [b], a swap of two neighbours counting as one. */
private fun typos(a: String, b: String): Int {
    val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..a.length) for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[a.length][b.length]
}
