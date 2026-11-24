package com.finanzaspersonales.core.common

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Toda fecha se guarda como [Instant] (UTC) y se interpreta en hora de Colombia. */
object BogotaTime {
    val ZONE: ZoneId = ZoneId.of("America/Bogota")

    fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZONE))

    fun dateOf(instant: Instant): LocalDate = instant.atZone(ZONE).toLocalDate()

    /** Rango [inicio, fin) del mes que contiene [date], en instantes UTC. */
    fun monthRange(date: LocalDate): ClosedOpenRange {
        val month = YearMonth.from(date)
        return ClosedOpenRange(
            start = month.atDay(1).atStartOfDay(ZONE).toInstant(),
            endExclusive = month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant(),
        )
    }

    /** Rango [inicio, fin) del día [date]. */
    fun dayRange(date: LocalDate): ClosedOpenRange = ClosedOpenRange(
        start = date.atStartOfDay(ZONE).toInstant(),
        endExclusive = date.plusDays(1).atStartOfDay(ZONE).toInstant(),
    )
}

data class ClosedOpenRange(val start: Instant, val endExclusive: Instant) {
    operator fun contains(instant: Instant): Boolean = instant >= start && instant < endExclusive
}
