package com.finanzaspersonales.engine.finance

import com.finanzaspersonales.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightsTest {
    private val today = LocalDate.of(2026, 11, 24)
    private fun pesos(p: Long) = Money.ofPesos(p)
    private var nextId = 1L
    private fun e(date: LocalDate, p: Long, desc: String, cat: Long? = 1, catName: String? = "Alimentación") =
        Expense(nextId++, date, pesos(p), cat, catName, desc)

    @Test
    fun claveAgrupaVariantesDelMismoComercio() {
        assertEquals(SpendingPatterns.key("NETFLIX.COM 1234"), SpendingPatterns.key("Netflix com"))
        assertEquals("cafe", SpendingPatterns.key("Café"))
    }

    @Test
    fun gastosHormigaNecesitanVariosGastosPequenos() {
        val few = (1..5).map { e(today.minusDays(it.toLong()), 6_000, "Café") }
        assertNull(SpendingPatterns.antExpenses(few, today))
        val many = few + (1..4).map { e(today.minusDays(it.toLong()), 3_500, "Dulces") } + e(today, 90_000, "Mercado")
        val ants = SpendingPatterns.antExpenses(many, today)!!
        assertEquals(9, ants.count)
        assertEquals(pesos(44_000), ants.total)
        assertEquals("Café", ants.top.first().first)
    }

    @Test
    fun recurrenteMensualSeDetectaYElDiarioNo() {
        val netflix = (0..3L).map { e(today.minusMonths(it).withDayOfMonth(5), 26_900 + it * 100, "Netflix", 9, "Suscripciones") }
        val cafe = (0..90L).map { e(today.minusDays(it), 5_000, "Café") }
        val found = SpendingPatterns.recurring(netflix + cafe, today)
        assertEquals(listOf("Netflix"), found.map { it.name })
        assertEquals(4, found.single().months)
        assertEquals(5, found.single().typicalDay)
    }

    @Test
    fun gastoInusualEsTresVecesLoNormal() {
        val history = (1..6).map { e(today.minusDays(40L + it), 20_000, "Almuerzo") }
        val big = e(today.minusDays(2), 180_000, "Restaurante")
        val normal = e(today.minusDays(1), 25_000, "Almuerzo")
        val found = SpendingPatterns.unusual(history + big + normal, today)
        assertEquals(listOf(big.id), found.map { it.expense.id })
        assertEquals(9, found.single().times)
    }

    @Test
    fun tendenciaProyectaElMesAlRitmoActual() {
        // Día 24 de 30: 400.000 -> 500.000 proyectado vs 350.000 de promedio.
        val trends = SpendingPatterns.trends(mapOf(1L to pesos(400_000)), mapOf(1L to pesos(350_000)), mapOf(1L to ("Alimentación" to true)), today)
        assertEquals(pesos(500_000), trends.single().projected)
        assertEquals(42, trends.single().changePercent)
        assertTrue(SpendingPatterns.trends(mapOf(1L to pesos(10_000)), mapOf(1L to pesos(350_000)), mapOf(1L to ("A" to true)), today).isEmpty())
        assertTrue(SpendingPatterns.trends(mapOf(1L to pesos(400_000)), mapOf(1L to pesos(350_000)), mapOf(1L to ("A" to true)), LocalDate.of(2026, 11, 3)).isEmpty())
    }

    @Test
    fun simuladorProtegeElDiaADia() {
        // Disponible 1.000.000; gasto diario 20.000 por 10 días = 200.000.
        val ok = PurchaseSimulator.analyze(pesos(300_000), pesos(1_000_000), pesos(20_000), 10, pesos(400_000), pesos(0))
        assertEquals(PurchaseVerdict.YES, ok.verdict)
        assertEquals(pesos(500_000), ok.leftAfter)
        assertNull(ok.monthsToSave)

        val tight = PurchaseSimulator.analyze(pesos(760_000), pesos(1_000_000), pesos(20_000), 10, pesos(400_000), pesos(0))
        assertEquals(PurchaseVerdict.CAREFUL, tight.verdict)

        val no = PurchaseSimulator.analyze(pesos(1_500_000), pesos(1_000_000), pesos(20_000), 10, pesos(400_000), pesos(500_000))
        assertEquals(PurchaseVerdict.WAIT, no.verdict)
        assertEquals(pesos(-700_000), no.leftAfter)
        // Le faltan 1.500.000 - (1.000.000 - 200.000 - 50.000) = 750.000 -> 2 meses con 400.000.
        assertEquals(2, no.monthsToSave)
        assertTrue(no.reasons.any { it.contains("fondo de emergencia") })
    }

    private fun input() = AdvisorInput(
        today = today, monthsAnalyzed = 3, available = pesos(500_000), projectedEndOfMonth = pesos(200_000),
        monthlySurplus = pesos(300_000), averageEssential = pesos(1_000_000), budgetsOver = emptyList(), budgetsNear = emptyList(),
        trends = emptyList(), upcoming = emptyList(), ants = null, unusual = emptyList(), recurringNotInCalendar = emptyList(),
        debts = emptyList(), emergencySaved = Money.ZERO, emergencyTarget = Money.ZERO, nonEssentialAverages = emptyList(),
    )

    @Test
    fun asesorSinProblemasSoloSugiereElFondo() {
        val tips = Advisor.build(input())
        assertEquals(listOf(InsightKind.EMERGENCY), tips.map { it.kind })
    }

    @Test
    fun asesorOrdenaAlertasPrimeroYPriorizaLaDeudaMasCara() {
        val tips = Advisor.build(
            input().copy(
                available = pesos(-100_000),
                budgetsOver = listOf(BudgetAlert("Entretenimiento", pesos(220_000), pesos(200_000))),
                upcoming = listOf(UpcomingPayment("Arriendo", pesos(800_000), today.plusDays(3)), UpcomingPayment("Luz", pesos(90_000), today.plusDays(20))),
                debts = listOf(
                    DebtSummary("Tarjeta", pesos(2_000_000), 2_800, RateType.EFFECTIVE_ANNUAL),
                    DebtSummary("Libre inversión", pesos(5_000_000), 1_900, RateType.EFFECTIVE_ANNUAL),
                ),
                emergencySaved = pesos(500_000), emergencyTarget = pesos(3_000_000),
                nonEssentialAverages = listOf("Entretenimiento" to pesos(250_000)),
            ),
        )
        assertEquals(InsightSeverity.ALERT, tips[0].severity)
        assertEquals(InsightKind.CASH, tips[0].kind)
        assertEquals(InsightKind.BUDGET, tips[1].kind)
        val upcoming = tips.first { it.kind == InsightKind.UPCOMING }
        assertEquals("Tienes 1 pago esta semana", upcoming.title)
        assertEquals("Prioriza Tarjeta", tips.first { it.kind == InsightKind.DEBT }.title)
        assertTrue(tips.first { it.kind == InsightKind.EMERGENCY }.body.contains("90.000"))
        assertTrue(tips.first { it.kind == InsightKind.SAVINGS }.body.contains("25.000"))
    }
}
