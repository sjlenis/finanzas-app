package com.finanzaspersonales.feature.transactions

import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.designsystem.component.formatShortDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val MonthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-CO"))

/** Texto del periodo visible: "Hoy", "17 nov – 23 nov", "Noviembre 2026", "2026". */
fun periodLabel(period: Period, anchor: LocalDate, today: LocalDate): String = when (period) {
    Period.DAY -> when (anchor) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> formatShortDate(anchor)
    }
    Period.WEEK -> {
        val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        "${formatShortDate(monday)} – ${formatShortDate(monday.plusDays(6))}"
    }
    Period.MONTH -> MonthYear.format(anchor).replaceFirstChar { it.uppercase() }
    Period.YEAR -> anchor.year.toString()
}
