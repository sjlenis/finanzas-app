package com.finanzaspersonales.engine.nlp

import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MessageParserTest {

    // Martes 24 de noviembre de 2026.
    private val today = LocalDate.of(2026, 11, 24)
    private val context = ParseContext(
        today = today,
        accountNames = listOf("Bancolombia", "Nequi", "Efectivo"),
        categoryNames = listOf(
            "Alimentación", "Transporte", "Vivienda", "Servicios públicos", "Salud", "Educación", "Entretenimiento",
            "Compras personales", "Ropa y accesorios", "Suscripciones", "Deudas", "Emergencias", "Otros",
            "Ingresos laborales", "Ingresos adicionales", "Préstamos", "Transferencias",
        ),
    )
    private val parser = MessageParser()

    private fun entry(msg: String): EntryDraft {
        val r = parser.parse(msg, context)
        assertTrue("'$msg' debería ser un movimiento, fue $r", r is ParseResult.Entry)
        return (r as ParseResult.Entry).draft
    }

    private fun query(msg: String): FinanceQuery {
        val r = parser.parse(msg, context)
        assertTrue("'$msg' debería ser una consulta, fue $r", r is ParseResult.Query)
        return (r as ParseResult.Query).query
    }

    // --- Los ejemplos del requerimiento ------------------------------------------

    @Test fun almuerzo() = with(entry("Hoy gasté 15.000 pesos en almuerzo")) {
        assertEquals(DraftKind.EXPENSE, kind)
        assertEquals(Money.ofPesos(15_000), amount)
        assertEquals("Alimentación", categoryName)
        assertEquals("Almuerzo", description)
        assertEquals(today, date)
        assertNull(accountName)
    }

    @Test fun gasolina() = with(entry("Pagué 40.000 de gasolina")) {
        assertEquals(DraftKind.EXPENSE, kind)
        assertEquals(Money.ofPesos(40_000), amount)
        assertEquals("Transporte", categoryName)
        assertEquals("Gasolina", description)
    }

    @Test fun ropa() = with(entry("Compré ropa por 120.000")) {
        assertEquals(Money.ofPesos(120_000), amount)
        assertEquals("Ropa y accesorios", categoryName)
    }

    @Test fun cafe() = with(entry("Gasté 8.000 en un café")) {
        assertEquals(Money.ofPesos(8_000), amount)
        assertEquals("Alimentación", categoryName)
        assertEquals("Café", description)
    }

    @Test fun transporteAyer() = with(entry("Ayer pagué 25.000 de transporte")) {
        assertEquals(today.minusDays(1), date)
        assertEquals(Money.ofPesos(25_000), amount)
        assertEquals("Transporte", categoryName)
    }

    @Test fun ingresoPorTrabajo() = with(entry("Recibí 250.000 por un trabajo")) {
        assertEquals(DraftKind.INCOME, kind)
        assertEquals(Money.ofPesos(250_000), amount)
        assertEquals("Ingresos adicionales", categoryName)
        assertEquals("Trabajo", description)
    }

    @Test fun prestamoAUnAmigo() = with(entry("Le presté 100.000 pesos a un amigo")) {
        assertEquals(DraftKind.LOAN_GIVEN, kind)
        assertEquals(Money.ofPesos(100_000), amount)
        assertEquals("Un amigo", counterparty)
        assertEquals("Préstamo a un amigo", description)
        assertEquals("Préstamos", categoryName)
    }

    @Test fun devolucion() = with(entry("Me devolvieron 30.000 de una compra")) {
        assertEquals(DraftKind.REFUND, kind)
        assertEquals(Money.ofPesos(30_000), amount)
        assertEquals("Compra", description)
    }

    @Test fun servicios() = with(entry("Pagué 80.000 de servicios")) {
        assertEquals(Money.ofPesos(80_000), amount)
        assertEquals("Servicios públicos", categoryName)
    }

    // --- Variantes colombianas ------------------------------------------------------

    @Test fun formasDeEscribirValores() {
        val cases = mapOf(
            "gasté 15 mil en almuerzo" to 15_000L,
            "gasté 15mil en almuerzo" to 15_000L,
            "gasté 15k en almuerzo" to 15_000L,
            "gasté 20 lucas en cine" to 20_000L,
            "pagué $1.250.000 de arriendo" to 1_250_000L,
            "pagué 1,5 millones de arriendo" to 1_500_000L,
            "pagué 2 millones de matrícula" to 2_000_000L,
            "me pagaron un millon de la quincena" to 1_000_000L,
            "pagué 3500 de bus" to 3_500L,
        )
        cases.forEach { (msg, pesos) -> assertEquals(msg, Money.ofPesos(pesos), entry(msg).amount) }
    }

    @Test fun centavos() = assertEquals(Money(1_550_050), entry("pagué 15.500,50 de bus").amount)

    @Test fun cuentaMencionada() {
        assertEquals("Nequi", entry("pagué 12.000 de taxi con nequi").accountName)
        assertEquals("Efectivo", entry("compré pan por 5.000 en efectivo").accountName)
        assertEquals("Bancolombia", entry("pagué 80.000 de servicios por bancolombia").accountName)
    }

    @Test fun fechasRelativas() {
        assertEquals(today.minusDays(2), entry("antier gasté 10.000 en taxi").date)
        assertEquals(today.minusDays(3), entry("hace 3 días pagué 9.000 de bus").date)
        assertEquals(LocalDate.of(2026, 11, 20), entry("el viernes gasté 30.000 en cine").date)
        assertEquals(LocalDate.of(2026, 11, 15), entry("el 15 pagué 50.000 de luz").date)
        assertEquals(LocalDate.of(2026, 10, 28), entry("el 28 pagué 50.000 de luz").date) // 28 aún no llega: mes pasado
    }

    @Test fun laFechaNoSeConfundeConElValor() =
        assertEquals(Money.ofPesos(50_000), entry("el 15 de noviembre pagué 50.000 de luz").amount)

    @Test fun retiroEsTransferenciaAEfectivo() = with(entry("saqué 200.000 del cajero")) {
        assertEquals(DraftKind.WITHDRAWAL, kind)
        assertEquals(Money.ofPesos(200_000), amount)
    }

    @Test fun comision() = assertEquals(DraftKind.FEE, entry("me cobraron 4x1000 de 2.000").kind)

    @Test fun prestamoRecibido() = assertEquals(DraftKind.LOAN_RECEIVED, entry("mi hermano me prestó 50.000").kind)

    @Test fun categoriaPropiaDelUsuario() {
        val ctx = context.copy(categoryNames = context.categoryNames + "Mascotas")
        val r = parser.parse("gasté 40.000 en concentrado para mascotas", ctx) as ParseResult.Entry
        assertEquals("Mascotas", r.draft.categoryName)
    }

    @Test fun sinValorPideElValor() = with(entry("gasté en almuerzo")) {
        assertNull(amount)
        assertEquals("Alimentación", categoryName)
    }

    @Test fun sinCategoriaConocida() = assertNull(entry("pagué 9.000 en cosas varias").categoryName)

    @Test fun mensajeSinSentido() = assertTrue(parser.parse("hola qué tal el clima", context) !is ParseResult.Entry)

    // --- Consultas -------------------------------------------------------------------

    @Test fun consultasDeGasto() {
        assertEquals(FinanceQuery.Spent(Period.DAY, null), query("¿Cuánto he gastado hoy?"))
        assertEquals(FinanceQuery.Spent(Period.MONTH, null), query("¿Cuánto llevo gastado este mes?"))
        assertEquals(FinanceQuery.Spent(Period.MONTH, "Alimentación"), query("¿Cuánto he gastado en alimentación?"))
        assertEquals(FinanceQuery.Spent(Period.WEEK, "Transporte"), query("cuánto gasté esta semana en transporte?"))
    }

    @Test fun otrasConsultas() {
        assertEquals(FinanceQuery.TotalMoney, query("¿Cuánto dinero tengo disponible?"))
        assertEquals(FinanceQuery.Received(Period.MONTH), query("¿Cuánto dinero he recibido?"))
        assertEquals(FinanceQuery.Cash, query("¿Cuánto tengo en efectivo?"))
        assertEquals(FinanceQuery.TopExpenses(Period.MONTH), query("¿Cuáles son mis gastos más altos?"))
        assertEquals(FinanceQuery.WeeklyAllowance, query("¿Cuánto puedo gastar esta semana?"))
        assertEquals(FinanceQuery.UpcomingPayments, query("¿Cuáles son mis próximas obligaciones?"))
        assertEquals(FinanceQuery.SavingsGoals, query("¿Cómo van mis metas de ahorro?"))
        assertEquals(FinanceQuery.AccountBalance("Nequi"), query("¿cuánto tengo en Nequi?"))
    }

    @Test fun ayuda() = assertEquals(ParseResult.Help, parser.parse("Hola", context))

    @Test fun atajoCorto() {
        val d = entry("Almuerzo 15000")
        assertEquals(Money.ofPesos(15_000), d.amount)
        assertEquals("Almuerzo", d.description)
        assertEquals("Alimentación", d.categoryName)
        assertEquals(DraftKind.EXPENSE, d.kind)
    }

    @Test fun atajoCafe() {
        val d = entry("Café 5 mil")
        assertEquals(Money.ofPesos(5_000), d.amount)
        assertEquals("Café", d.description)
    }

    @Test fun respuestaDeSoloValor() {
        assertEquals(Money.ofPesos(15_000), parser.parseAmountReply("15.000"))
        assertEquals(Money.ofPesos(20_000), parser.parseAmountReply("fueron 20 mil"))
        assertEquals(Money.ofPesos(8_500), parser.parseAmountReply("$8.500 pesos"))
        assertEquals(null, parser.parseAmountReply("gasté 15.000 en almuerzo"))
        assertEquals(null, parser.parseAmountReply("¿cuánto he gastado hoy?"))
    }

    @Test fun cuantoLlevoEsteMes() =
        assertEquals(FinanceQuery.Spent(com.finanzaspersonales.core.common.Period.MONTH, null), query("¿Cuánto llevo este mes?"))

    @Test fun compraSinPreposicion() = assertEquals("Mercado", entry("Compré un mercado").description)
}
