package com.finanzaspersonales.engine.nlp

import java.text.Normalizer

/** Minúsculas, sin tildes y con espacios simples: "Pagué  Gasolina" -> "pague gasolina". */
internal fun normalize(text: String): String {
    val noAccents = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return noAccents.replace('ñ', 'n').replace(Regex("\\s+"), " ").trim()
}

internal fun String.containsWord(word: String): Boolean =
    Regex("(^|[^a-z0-9])${Regex.escape(word)}([^a-z0-9]|$)").containsMatchIn(this)

internal fun String.containsAnyWord(words: Collection<String>): Boolean = words.any { containsWord(it) }

internal fun String.capitalizeFirst(): String = replaceFirstChar { it.uppercase() }
