package app.ytune

import app.ytune.data.Language

/**
 * The app speaks French (the default) or English, picked in Settings → Appearance → Language.
 * Each piece of UI text is written in both languages where it's used:
 * `tr("Play all", "Tout lire")`. Changing the language recreates the activity, so everything
 * on screen is read again.
 */
fun tr(en: String, fr: String): String = if (isFrench) fr else en

val isFrench: Boolean get() = Graph.settings.current.language == Language.FRENCH

/**
 * "[n] [word]" with the right plural: "1 track" / "0 tracks" / "3 tracks" in English,
 * "0 titre" / "1 titre" / "3 titres" in French (0 and 1 are singular there).
 */
fun trCount(n: Long, enOne: String, enMany: String, frOne: String, frMany: String): String =
    if (isFrench) "$n ${if (n <= 1) frOne else frMany}" else "$n ${if (n == 1L) enOne else enMany}"

fun trCount(n: Int, enOne: String, enMany: String, frOne: String, frMany: String): String =
    trCount(n.toLong(), enOne, enMany, frOne, frMany)
