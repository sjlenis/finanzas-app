package com.finanzaspersonales.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class BogotaTimeTest {

    @Test
    fun `a las 9 pm en Bogota sigue siendo el mismo dia aunque en UTC ya sea manana`() {
        // 2026-11-25T02:00Z = 2026-11-24 21:00 en Bogotá (UTC-5).
        val clock = Clock.fixed(Instant.parse("2026-11-25T02:00:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 11, 24), BogotaTime.today(clock))
    }

    @Test
    fun `rango del mes usa medianoche de Colombia`() {
        val range = BogotaTime.monthRange(LocalDate.of(2026, 11, 15))
        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-12-01T05:00:00Z"), range.endExclusive)
        assertTrue(Instant.parse("2026-12-01T04:59:59Z") in range)
        assertFalse(Instant.parse("2026-12-01T05:00:00Z") in range)
    }
}
