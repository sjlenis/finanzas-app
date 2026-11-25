package com.finanzaspersonales.engine.nlp

import com.finanzaspersonales.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DocumentReaderTest {
    private val today = LocalDate.of(2026, 11, 24)

    /** Etiqueta a la izquierda y valor a la derecha, como en las apps bancarias. */
    private fun row(y: Int, label: String, value: String? = null): List<OcrLine> = listOfNotNull(
        OcrLine(label, 60, y, 400, y + 40),
        value?.let { OcrLine(it, 620, y + 3, 1300, y + 43) },
    )

    @Test
    fun capturaDeCreditoComoLaDelEjemplo() {
        val lines = row(240, "Info de tu crédito") +
            row(520, "primer crédito") + row(585, "Crédito Bajo Monto") +
            row(770, "Debes", "$ 2.583.328,84") + row(950, "Cuotas pagadas", "4 de 31") +
            row(1270, "Fecha de pago", "22 de cada mes") + row(1440, "Capital Vigente", "$ 2.583.328,84") +
            row(1600, "Intereses corrientes", "$ 11.686,72") + row(1770, "Intereses de mora", "$ 0,00") +
            row(1930, "Seguros y otros cargos", "$ 8.000,00") + row(2100, "Fecha de desembolso", "26 de junio de 2026") +
            row(2270, "No. crédito", "87301185276") + row(2430, "Tasa de Interés", "2.79% E.M / 39.11% E.A") +
            row(2760, "Pagar crédito")
        val d = DocumentReader.read(lines, today) as DocumentResult.Debt
        assertEquals("Crédito Bajo Monto (primer crédito)", d.name)
        assertEquals(Money(258_332_884), d.balance)
        assertNull(d.installment)
        assertEquals(22, d.dueDay)
        assertEquals(3911, d.rateBasisPoints)
        assertEquals(DocRate.EFFECTIVE_ANNUAL, d.rateType)
        assertEquals(27, d.remainingInstallments)
        assertEquals(Money.ofPesos(8_000), d.insurance)
        assertEquals(Money(1_168_672), d.currentInterest)
        assertEquals(LocalDate.of(2026, 6, 26), d.disbursedOn)
        assertEquals("••••5276", d.maskedNumber)
    }

    @Test
    fun centavosPartidosEnOtraLineaNoConfundenElSaldo() {
        val lines = row(100, "Crédito libre inversión") + row(300, "Debes", "$ 1.200.000,") + row(300, "", null) +
            listOf(OcrLine("50", 1310, 300, 1350, 330)) +
            row(500, "Saldo pendiente", "$ 1.200.000,50") + row(700, "Valor cuota", "$ 150.000") + row(900, "Tasa", "1,9% M.V.")
        val d = DocumentReader.read(lines, today) as DocumentResult.Debt
        assertEquals(Money(120_000_050), d.balance)
        assertEquals(Money.ofPesos(150_000), d.installment)
        assertEquals(190, d.rateBasisPoints)
        assertEquals(DocRate.MONTHLY, d.rateType)
    }

    @Test
    fun comprobanteDeEnvioNequi() {
        val lines = row(100, "¡Listo! Envío exitoso") + row(300, "Para", "Juan Pérez") + row(400, "¿Cuánto?", "$ 50.000,00") +
            row(500, "Fecha", "20 de noviembre de 2026, 3:15 p. m.") + row(600, "Referencia", "M1234567") + row(700, "Nequi")
        val m = DocumentReader.read(lines, today) as DocumentResult.Movement
        assertEquals(DraftKind.EXPENSE, m.draft.kind)
        assertEquals(Money.ofPesos(50_000), m.draft.amount)
        assertEquals("Juan Pérez", m.draft.description)
        assertEquals(LocalDate.of(2026, 11, 20), m.draft.date)
        assertEquals("Nequi", m.draft.accountName)
    }

    @Test
    fun facturaUsaElTotalYNoElSubtotal() {
        val lines = row(100, "SUPERMERCADO LA 14") + row(200, "NIT 900.123.456-7") + row(300, "Factura electrónica de venta") +
            row(400, "Fecha: 22/11/2026") + row(500, "Subtotal", "$ 38.571") + row(600, "IVA", "$ 7.329") + row(700, "TOTAL", "$ 45.900")
        val m = DocumentReader.read(lines, today, listOf("Alimentación")) as DocumentResult.Movement
        assertEquals(Money.ofPesos(45_900), m.draft.amount)
        assertEquals("SUPERMERCADO LA 14", m.draft.description)
        assertEquals(LocalDate.of(2026, 11, 22), m.draft.date)
    }

    @Test
    fun dineroRecibidoEsIngreso() {
        val lines = row(100, "Recibiste plata") + row(200, "De", "María Gómez") + row(300, "Valor", "$ 120.000")
        val m = DocumentReader.read(lines, today) as DocumentResult.Movement
        assertEquals(DraftKind.INCOME, m.draft.kind)
        assertEquals("De María Gómez", m.draft.description)
    }

    @Test
    fun sinTextoOSinValorNoInventa() {
        assertTrue(DocumentReader.read(emptyList(), today) is DocumentResult.Unknown)
        assertTrue(DocumentReader.read(row(100, "Hola mundo"), today) is DocumentResult.Unknown)
    }
}
