package com.finanzaspersonales.engine.nlp

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Encuentra la fecha en un texto normalizado. Nunca devuelve una fecha futura:
 * los movimientos se registran cuando ya ocurrieron.
 */
internal object DateExtractor {

    data class Found(val date: LocalDate, val range: IntRange?)

    private val months = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val weekdays = mapOf(
        "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY, "miercoles" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY,
    )

    private val dayOfMonth = Regex("\\bel (\\d{1,2})(?: de (${months.joinToString("|")}))?\\b")
    private val daysAgo = Regex("\\bhace (\\d{1,2}|un|dos|tres) dias?\\b")
    private val weekday = Regex("\\b(?:el )?(?:pasado )?(${weekdays.keys.joinToString("|")})(?: pasado)?\\b")

    fun find(text: String, today: LocalDate): Found {
        Regex("\\b(antier|anteayer|antes de ayer)\\b").find(text)?.let { return Found(today.minusDays(2), it.range) }
        Regex("\\bayer\\b").find(text)?.let { return Found(today.minusDays(1), it.range) }
        daysAgo.find(text)?.let { m ->
            val n = when (val v = m.groupValues[1]) { "un" -> 1L; "dos" -> 2L; "tres" -> 3L; else -> v.toLong() }
            return Found(today.minusDays(n), m.range)
        }
        dayOfMonth.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val monthName = m.groupValues[2]
            if (day in 1..31) {
                var month = if (monthName.isEmpty()) today.monthValue else months.indexOf(monthName) + 1
                var year = today.year
                var candidate = safeDate(year, month, day)
                if (candidate != null && candidate.isAfter(today)) {
                    // "el 28" dicho el día 5 se refiere al mes pasado; "el 28 de diciembre" en noviembre, al año pasado.
                    if (monthName.isEmpty()) { month -= 1; if (month == 0) { month = 12; year -= 1 } } else year -= 1
                    candidate = safeDate(year, month, day)
                }
                if (candidate != null) return Found(candidate, m.range)
            }
        }
        weekday.find(text)?.let { m ->
            val target = weekdays.getValue(m.groupValues[1])
            var d = today.minusDays(1)
            while (d.dayOfWeek != target) d = d.minusDays(1)
            return Found(d, m.range)
        }
        Regex("\\bhoy\\b").find(text)?.let { return Found(today, it.range) }
        return Found(today, null)
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()
}
