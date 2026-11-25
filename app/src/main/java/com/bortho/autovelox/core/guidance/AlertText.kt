package com.bortho.autovelox.core.guidance

import kotlin.math.roundToInt

/**
 * The spoken alert, from a template the user can edit on the phone. Placeholders: `{distanza}` ("300 metri"),
 * `{limite}` (the number) and `{velocita}` (the current speed, also accepted as `{velocità}`). When the camera's limit
 * is unknown, the sentence that contains `{limite}` is dropped as a whole, so nothing like "Limite chilometri orari" is
 * ever spoken.
 */
object AlertText {
    const val DEFAULT_TEMPLATE = "Attenzione: autovelox a {distanza}. Limite {limite} chilometri orari."
    const val MAX_LENGTH = 200

    val PLACEHOLDERS = listOf("{distanza}", "{limite}", "{velocita}")

    fun render(template: String, distanceMeters: Double, limitKmh: Int?, speedKmh: Double): String {
        var text = template.ifBlank { DEFAULT_TEMPLATE }.take(MAX_LENGTH)
        if (limitKmh == null) text = dropSentencesWith(text, "{limite}")
        return text
            .replace("{distanza}", spokenDistance(distanceMeters))
            .replace("{limite}", limitKmh?.toString().orEmpty())
            .replace("{velocita}", speedKmh.roundToInt().toString())
            .replace("{velocità}", speedKmh.roundToInt().toString())
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun spokenDistance(meters: Double): String = when {
        meters >= 1500 -> "${(meters / 1000).roundToInt()} chilometri"
        meters >= 950 -> "un chilometro"
        else -> "${(meters / 10).roundToInt() * 10} metri"
    }

    /** Splits after . ! ? (keeping the mark) and drops every sentence that contains [marker]. */
    private fun dropSentencesWith(text: String, marker: String): String =
        Regex("[^.!?]*[.!?]+|[^.!?]+$").findAll(text).map { it.value }.filterNot { marker in it }.joinToString("")
}
