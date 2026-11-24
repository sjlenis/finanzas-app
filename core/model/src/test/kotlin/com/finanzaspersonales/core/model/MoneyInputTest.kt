package com.finanzaspersonales.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyInputTest {

    @Test
    fun `acepta formatos colombianos comunes`() {
        assertEquals(Money.ofPesos(15_000), MoneyInput.parse("15000"))
        assertEquals(Money.ofPesos(15_000), MoneyInput.parse("15.000"))
        assertEquals(Money.ofPesos(15_000), MoneyInput.parse("$15.000"))
        assertEquals(Money.ofPesos(15_000), MoneyInput.parse(" $ 15.000 "))
        assertEquals(Money.ofPesos(1_250_000), MoneyInput.parse("1.250.000"))
        assertEquals(Money(1_500_050), MoneyInput.parse("15.000,50"))
        assertEquals(Money(1_500_050), MoneyInput.parse("15000,5"))
    }

    @Test
    fun `rechaza textos ambiguos o invalidos`() {
        listOf("", "abc", "15.00", "1.2345", ".500", "15,000,00", "15,", "15,123", "-500", "1e5", "9999999999999")
            .forEach { assertNull("debería rechazar '$it'", MoneyInput.parse(it)) }
    }

    @Test
    fun `texto editable sin simbolo ni signo`() {
        assertEquals("18.000", MoneyInput.toEditableText(Money.ofPesos(-18_000)))
    }

    @Test
    fun `el tipo decide el signo`() {
        val v = Money.ofPesos(18_000)
        assertEquals(-v, ManualEntryType.EXPENSE.signedAmount(v))
        assertEquals(-v, ManualEntryType.FEE.signedAmount(v))
        assertEquals(v, ManualEntryType.INCOME.signedAmount(v))
        assertEquals(v, ManualEntryType.REFUND.signedAmount(v))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `no se registran valores en cero`() {
        ManualEntryType.EXPENSE.signedAmount(Money.ZERO)
    }
}
