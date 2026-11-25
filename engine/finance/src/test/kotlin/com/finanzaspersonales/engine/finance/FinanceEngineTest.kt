package com.finanzaspersonales.engine.finance

import com.finanzaspersonales.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class FinanceEngineTest {
    private val start = YearMonth.of(2026, 12)
    private fun pesos(p: Long) = Money.ofPesos(p)

    private val tarjeta = DebtInput(1, "Tarjeta", pesos(3_000_000), 2_800, RateType.EFFECTIVE_ANNUAL, pesos(200_000))
    private val libranza = DebtInput(2, "Libranza", pesos(8_000_000), 1_600, RateType.EFFECTIVE_ANNUAL, pesos(350_000))
    private val amigo = DebtInput(3, "Préstamo de Juan", pesos(500_000), 0, RateType.NONE, pesos(100_000))

    @Test fun tasaEfectivaAnualAMensual() {
        // 12,68 % E.A. ≈ 1 % mensual.
        val monthly = Rates.monthlyRate(1_268, RateType.EFFECTIVE_ANNUAL)
        assertEquals(0.01, monthly.toDouble(), 0.0001)
        assertEquals(pesos(10_000), Rates.interestFor(pesos(1_000_000), java.math.BigDecimal("0.01")))
    }

    @Test fun deudaSinInteresSeTerminaEnCuotasExactas() {
        val plan = DebtPlanner.plan(listOf(amigo), Money.ZERO, DebtStrategy.SNOWBALL, start)
        assertEquals(5, plan.months)
        assertEquals(Money.ZERO, plan.totalInterest)
        assertEquals(pesos(500_000), plan.totalPaid)
        assertEquals(YearMonth.of(2027, 4), plan.debtFreeMonth)
    }

    @Test fun bolaDeNieveTerminaPrimeroLaMasPequena() {
        val plan = DebtPlanner.plan(listOf(tarjeta, libranza, amigo), pesos(100_000), DebtStrategy.SNOWBALL, start)
        assertEquals("Préstamo de Juan", plan.payoffs.first().name)
        assertTrue(plan.feasible)
    }

    @Test fun avalanchaPagaMenosInteresesQueBolaDeNieve() {
        val c = DebtPlanner.compare(listOf(tarjeta, libranza, amigo), pesos(150_000), start)
        assertTrue(c.avalanche.totalInterest <= c.snowball.totalInterest)
        assertEquals("Tarjeta", c.avalanche.payoffs.first { it.name != "Préstamo de Juan" }.name)
        assertEquals(DebtStrategy.AVALANCHE, c.recommended.strategy)
    }

    @Test fun pagoExtraAhorraIntereses() {
        val c = DebtPlanner.compare(listOf(tarjeta, libranza), pesos(200_000), start)
        val saved = assertNotNullAndGet(c.interestSaved)
        assertTrue(saved.centavos > 0)
        assertTrue(c.recommended.months!! < c.minimumOnly.months!!)
    }

    @Test fun cuotaQueNoCubreInteresesSeAdvierte() {
        val mala = DebtInput(9, "Tarjeta cara", pesos(10_000_000), 600, RateType.MONTHLY, pesos(50_000))
        val plan = DebtPlanner.plan(listOf(mala), Money.ZERO, DebtStrategy.AVALANCHE, start)
        assertFalse(plan.feasible)
        assertTrue(plan.problems.any { it.contains("no alcanza") })
    }

    @Test fun fondoDeEmergenciaUsaGastosEsenciales() {
        val p = EmergencyFund.plan(pesos(1_500_000), 3, pesos(450_000), pesos(1_000_000), null, LocalDate.of(2026, 11, 25))
        assertEquals(pesos(4_500_000), p.target)
        assertEquals(10, p.percent)
        assertEquals(pesos(300_000), p.suggestedMonthly) // 30 % del excedente
        assertEquals(YearMonth.of(2028, 1), p.eta) // 4.050.000 / 300.000 = 13,5 -> 14 meses
    }

    @Test fun sinExcedenteNoSugiereAhorrar() {
        val p = EmergencyFund.plan(pesos(1_500_000), 3, Money.ZERO, pesos(-50_000), null, LocalDate.of(2026, 11, 25))
        assertEquals(Money.ZERO, p.suggestedMonthly)
        assertNull(p.eta)
        assertTrue(p.notes.isNotEmpty())
    }

    @Test fun metaConFecha() {
        val p = SavingsGoals.plan(pesos(3_000_000), pesos(600_000), LocalDate.of(2027, 5, 25), LocalDate.of(2026, 11, 25), pesos(500_000))
        assertEquals(6, p.monthsLeft)
        assertEquals(pesos(400_000), p.requiredMonthly)
        assertEquals(true, p.feasible)
        assertEquals(20, p.percent)
        val dura = SavingsGoals.plan(pesos(3_000_000), Money.ZERO, LocalDate.of(2027, 1, 25), LocalDate.of(2026, 11, 25), pesos(500_000))
        assertEquals(false, dura.feasible)
    }

    @Test fun estadoDePresupuesto() {
        assertEquals(BudgetLevel.OK, BudgetStatus(pesos(100_000), pesos(50_000)).level)
        assertEquals(BudgetLevel.NEAR, BudgetStatus(pesos(100_000), pesos(85_000)).level)
        assertEquals(BudgetLevel.OVER, BudgetStatus(pesos(100_000), pesos(120_000)).level)
        assertEquals(120, BudgetStatus(pesos(100_000), pesos(120_000)).percent)
    }

    @Test fun presupuestoRecortaPrimeroLoNoEsencial() {
        val history = listOf(
            CategoryHistory(1, "Alimentación", true, pesos(600_000)),
            CategoryHistory(2, "Transporte", true, pesos(200_000)),
            CategoryHistory(3, "Entretenimiento", false, pesos(400_000)),
        )
        val r = BudgetAdvisor.recommend(history, expectedIncome = pesos(1_500_000), commitments = pesos(500_000))
        val byName = r.suggestions.associateBy { it.name }
        assertEquals(pesos(600_000), byName.getValue("Alimentación").suggested)
        assertEquals(pesos(200_000), byName.getValue("Transporte").suggested)
        assertEquals(pesos(200_000), byName.getValue("Entretenimiento").suggested)
        assertTrue(r.unallocated.centavos >= 0)
        assertTrue(r.notes.isNotEmpty())
    }

    @Test fun dineroDisponibleNoRestaDosVeces() {
        val a = AvailableMoney(total = pesos(2_000_000), obligations = pesos(700_000), emergencyFund = pesos(200_000), goals = Money.ZERO)
        assertEquals(pesos(1_100_000), a.available)
    }

    @Test fun proyeccionFinDeMes() {
        val p = Projection.endOfMonth(pesos(1_000_000), pesos(500_000), pesos(300_000), pesos(20_000), LocalDate.of(2026, 11, 25))
        assertEquals(pesos(1_100_000), p) // 1.000.000 + 500.000 - 300.000 - 5 días × 20.000
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
