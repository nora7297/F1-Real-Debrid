package com.f1debrid.core.parse

import java.text.Normalizer

/**
 * One canonical name per Grand Prix, so "Mexico City", "Mexican" and "United Mexican States" (or
 * "USA", "US", "Austin" and "United States") from different uploaders land in the same group.
 */
object GrandPrix {
    data class Gp(val key: String, val display: String, val aliases: List<String>)

    val ALL: List<Gp> = listOf(
        Gp("australia", "Australian", listOf("australian", "australia", "melbourne", "albert park")),
        Gp("china", "Chinese", listOf("chinese", "china", "shanghai")),
        Gp("japan", "Japanese", listOf("japanese", "japan", "suzuka")),
        Gp("bahrain", "Bahrain", listOf("bahrain", "sakhir")),
        Gp("saudi-arabia", "Saudi Arabian", listOf("saudi arabian", "saudi arabia", "saudi", "jeddah")),
        Gp("miami", "Miami", listOf("miami")),
        Gp("canada", "Canadian", listOf("canadian", "canada", "montreal")),
        Gp("monaco", "Monaco", listOf("monaco", "monte carlo")),
        Gp("barcelona", "Barcelona-Catalunya", listOf("barcelona catalunya", "barcelona", "catalunya", "catalonia", "catalan")),
        Gp("austria", "Austrian", listOf("austrian", "austria", "spielberg", "red bull ring")),
        Gp("britain", "British", listOf("british", "great britain", "britain", "silverstone", "uk")),
        Gp("belgium", "Belgian", listOf("belgian", "belgium", "spa francorchamps", "spa")),
        Gp("hungary", "Hungarian", listOf("hungarian", "hungary", "hungaroring", "budapest")),
        Gp("netherlands", "Dutch", listOf("dutch", "netherlands", "holland", "zandvoort")),
        Gp("italy", "Italian", listOf("italian", "italy", "monza")),
        Gp("spain", "Spanish", listOf("spanish", "spain", "madrid", "madring")),
        Gp("azerbaijan", "Azerbaijan", listOf("azerbaijan", "baku")),
        Gp("singapore", "Singapore", listOf("singapore", "marina bay")),
        Gp("united-states", "United States", listOf("united states", "usa", "us", "american", "austin", "cota", "americas")),
        Gp(
            "mexico", "Mexico City",
            listOf("mexico city", "united mexican states", "mexican states", "mexican", "mexico", "ciudad de mexico"),
        ),
        Gp("brazil", "São Paulo", listOf("sao paulo", "brazilian", "brazil", "interlagos")),
        Gp("las-vegas", "Las Vegas", listOf("las vegas", "vegas")),
        Gp("qatar", "Qatar", listOf("qatar", "lusail")),
        Gp("abu-dhabi", "Abu Dhabi", listOf("abu dhabi", "yas marina")),
        Gp("emilia-romagna", "Emilia Romagna", listOf("emilia romagna", "imola")),
        Gp("portugal", "Portuguese", listOf("portuguese", "portugal", "portimao")),
        Gp("turkey", "Turkish", listOf("turkish", "turkey", "istanbul")),
    )

    private val MARKS = Regex("""\p{M}+""")
    private val NON_LETTERS = Regex("""[^a-z]+""")

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase().replace(NON_LETTERS, " ").trim()

    /** The Grand Prix a name refers to, or null when it is not recognised. */
    fun find(name: String?): Gp? {
        if (name.isNullOrBlank()) return null
        val n = normalize(name)
        if (n.isEmpty()) return null
        ALL.firstOrNull { gp -> gp.aliases.any { it == n } }?.let { return it }
        // Whole-word match inside a longer name, longest alias first; very short aliases must match exactly.
        val padded = " $n "
        return ALL.flatMap { gp -> gp.aliases.filter { it.length > 3 }.map { it to gp } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (alias, _) -> padded.contains(" $alias ") }
            ?.second
    }
}
