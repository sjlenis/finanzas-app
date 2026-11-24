package com.finanzaspersonales.core.common

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Periodos para consultar movimientos. La semana empieza el lunes, como en Colombia. */
enum class Period(val label: String) {
    DAY("Hoy"),
    WEEK("Semana"),
    MONTH("Mes"),
    YEAR("Año"),
    ;

    /** Rango [inicio, fin) del periodo que contiene [date], en hora de Colombia. */
    fun rangeContaining(date: LocalDate): ClosedOpenRange {
        val (start, endExclusive) = when (this) {
            DAY -> date to date.plusDays(1)
            WEEK -> {
                val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                monday to monday.plusWeeks(1)
            }
            MONTH -> date.withDayOfMonth(1).let { it to it.plusMonths(1) }
            YEAR -> date.withDayOfYear(1).let { it to it.plusYears(1) }
        }
        return ClosedOpenRange(
            start = start.atStartOfDay(BogotaTime.ZONE).toInstant(),
            endExclusive = endExclusive.atStartOfDay(BogotaTime.ZONE).toInstant(),
        )
    }
}
