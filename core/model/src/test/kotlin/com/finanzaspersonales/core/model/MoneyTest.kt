package com.finanzaspersonales.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun `ofPesos guarda centavos`() {
        assertEquals(1_800_000L, Money.ofPesos(18_000).centavos)
    }

    @Test
    fun `suma y resta sin perder precision`() {
        // Con Double, 0.1 + 0.2 != 0.3. Con centavos enteros siempre cuadra.
        val total = listOf(Money(10), Money(20)).sum()
        assertEquals(Money(30), total)
        assertEquals(Money.ofPesos(1_100_000),
            Money.ofPesos(2_000_000) - Money.ofPesos(300_000) - Money.ofPesos(400_000) - Money.ofPesos(200_000))
    }

    @Test(expected = ArithmeticException::class)
    fun `desbordamiento lanza error en vez de dar un saldo falso`() {
        Money(Long.MAX_VALUE) + Money(1)
    }

    @Test
    fun `formato colombiano`() {
        assertEquals("$18.000", Money.ofPesos(18_000).formatCop())
        assertEquals("$0", Money.ZERO.formatCop())
        assertEquals("$999", Money.ofPesos(999).formatCop())
        assertEquals("$1.000", Money.ofPesos(1_000).formatCop())
        assertEquals("$2.000.000", Money.ofPesos(2_000_000).formatCop())
        assertEquals("-$2.500,50", Money(-250_050).formatCop())
        assertEquals("15.000", Money.ofPesos(15_000).formatCop(withSymbol = false))
        assertEquals("$0,05", Money(5).formatCop())
    }

    @Test
    fun `formato no falla con el valor minimo`() {
        assertTrue(Money(Long.MIN_VALUE).formatCop().startsWith("-$"))
    }

    @Test
    fun `solo ingresos y gastos reales cuentan`() {
        assertTrue(TransactionKind.INCOME.countsAsIncome)
        assertTrue(TransactionKind.EXPENSE.countsAsExpense)
        listOf(TransactionKind.TRANSFER, TransactionKind.LOAN_GIVEN, TransactionKind.LOAN_RECEIVED,
            TransactionKind.ADJUSTMENT, TransactionKind.WITHDRAWAL).forEach {
            assertTrue(!it.countsAsIncome && !it.countsAsExpense)
        }
    }
}
