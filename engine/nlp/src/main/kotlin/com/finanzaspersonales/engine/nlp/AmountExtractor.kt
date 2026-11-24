package com.finanzaspersonales.engine.nlp

import com.finanzaspersonales.core.model.Money

/**
 * Encuentra el valor en pesos dentro de un texto normalizado.
 *
 * Reconoce: 15000, 15.000, $15.000, 15.000,50, 15 mil, 15mil, 15k, 15 lucas,
 * 1,5 millones, 1.5 millones, 2 millones, 2 palos, un millon, medio millon.
 */
internal object AmountExtractor {

    data class Found(val money: Money, val range: IntRange)

    private const val MILLION = 1_000_000L

    private val decimalMillions = Regex("(\\d+)[.,](\\d{1,3})\\s*(millon(?:es)?|palos?|m\\b)")
    private val wordMillions = Regex("\\b(un|medio)\\s+(millon|palo)\\b")
    private val number = Regex(
        "\\$?\\s?(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,(\\d{1,2}))?\\s*(mil\\b|k\\b|lucas?\\b|millon(?:es)?\\b|palos?\\b)?",
    )

    fun find(text: String): Found? {
        decimalMillions.find(text)?.let { m ->
            val whole = m.groupValues[1].toLong()
            val fraction = m.groupValues[2]
            val fractionValue = fraction.toLong() * MILLION / pow10(fraction.length)
            return Found(Money.ofPesos(whole * MILLION + fractionValue), m.range)
        }
        wordMillions.find(text)?.let { m ->
            val pesos = if (m.groupValues[1] == "medio") MILLION / 2 else MILLION
            return Found(Money.ofPesos(pesos), m.range)
        }
        for (m in number.findAll(text)) {
            val digits = m.groupValues[1].replace(".", "")
            val base = digits.toLongOrNull() ?: continue
            val cents = m.groupValues[2].takeIf { it.isNotEmpty() }?.padEnd(2, '0')?.toLong() ?: 0L
            val multiplier = when {
                m.groupValues[3].isEmpty() -> 1L
                m.groupValues[3] == "mil" || m.groupValues[3] == "k" || m.groupValues[3].startsWith("luca") -> 1_000L
                else -> MILLION
            }
            val pesos = base * multiplier
            if (pesos <= 0 || pesos > 999_999_999_999L) continue
            return Found(Money(pesos * 100 + if (multiplier == 1L) cents else 0L), m.range)
        }
        return null
    }

    private fun pow10(n: Int): Long = (1..n).fold(1L) { acc, _ -> acc * 10 }
}
