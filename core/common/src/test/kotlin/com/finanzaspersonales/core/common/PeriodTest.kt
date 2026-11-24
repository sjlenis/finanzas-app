package com.finanzaspersonales.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PeriodTest {
    private val martes = LocalDate.of(2026, 11, 24)

    @Test
    fun `semana va de lunes a lunes en hora de Colombia`() {
        val r = Period.WEEK.rangeContaining(martes)
        assertEquals(Instant.parse("2026-11-23T05:00:00Z"), r.start)
        assertEquals(Instant.parse("2026-11-30T05:00:00Z"), r.endExclusive)
    }

    @Test
    fun `dia mes y anio`() {
        assertEquals(Instant.parse("2026-11-25T05:00:00Z"), Period.DAY.rangeContaining(martes).endExclusive)
        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), Period.MONTH.rangeContaining(martes).start)
        assertEquals(Instant.parse("2026-01-01T05:00:00Z"), Period.YEAR.rangeContaining(martes).start)
        assertEquals(Instant.parse("2027-01-01T05:00:00Z"), Period.YEAR.rangeContaining(martes).endExclusive)
    }
}
