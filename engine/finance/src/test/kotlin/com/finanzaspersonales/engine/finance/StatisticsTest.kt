package com.finanzaspersonales.engine.finance

import com.finanzaspersonales.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class StatisticsTest {
    private fun pesos(p: Long) = Money.ofPesos(p)
    private val today = LocalDate.of(2026, 11, 24)

    @Test
    fun ultimosMesesEnOrden() {
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)), Statistics.lastMonths(today, 3))
    }

    @Test
    fun ahorroAcumuladoConRetiros() {
        val months = Statistics.lastMonths(today, 3)
        val events = listOf(
            LocalDate.of(2026, 8, 10) to pesos(100_000),
            LocalDate.of(2026, 10, 1) to pesos(50_000),
            LocalDate.of(2026, 10, 31) to pesos(-30_000),
            LocalDate.of(2026, 11, 20) to pesos(80_000),
        )
        assertEquals(listOf(pesos(100_000), pesos(120_000), pesos(200_000)), Statistics.cumulativeByMonth(events, months))
    }

    @Test
    fun deudaPorMesUsaElUltimoPagoYIgnoraDeudasFuturas() {
        val months = Statistics.lastMonths(today, 3)
        val tarjeta = Statistics.DebtHistory(
            LocalDate.of(2026, 9, 5), pesos(1_000_000),
            listOf(LocalDate.of(2026, 10, 15) to pesos(900_000), LocalDate.of(2026, 11, 15) to pesos(800_000)),
        )
        val nueva = Statistics.DebtHistory(LocalDate.of(2026, 11, 2), pesos(500_000), emptyList())
        assertEquals(listOf(pesos(1_000_000), pesos(900_000), pesos(1_300_000)), Statistics.debtByMonth(listOf(tarjeta, nueva), months))
    }
}
