package com.finanzaspersonales.core.model

/** Tipo de cuenta o medio de pago. */
enum class AccountType { BANK, WALLET, CASH, OTHER }

/**
 * Clase de movimiento. Solo [INCOME] y [EXPENSE] cuentan como ingresos o gastos reales;
 * transferencias, préstamos y ajustes mueven dinero pero no son ingreso ni gasto.
 */
enum class TransactionKind(val countsAsIncome: Boolean, val countsAsExpense: Boolean) {
    INCOME(countsAsIncome = true, countsAsExpense = false),
    EXPENSE(countsAsIncome = false, countsAsExpense = true),
    REFUND(countsAsIncome = false, countsAsExpense = false),
    FEE(countsAsIncome = false, countsAsExpense = true),
    TRANSFER(countsAsIncome = false, countsAsExpense = false),
    WITHDRAWAL(countsAsIncome = false, countsAsExpense = false),
    LOAN_GIVEN(countsAsIncome = false, countsAsExpense = false),
    LOAN_RECEIVED(countsAsIncome = false, countsAsExpense = false),
    LOAN_REPAYMENT(countsAsIncome = false, countsAsExpense = false),
    ADJUSTMENT(countsAsIncome = false, countsAsExpense = false),
}

/** De dónde vino un movimiento. Se conserva siempre. */
enum class TransactionOrigin { MANUAL, CHAT, NOTIFICATION, IMPORT, ADJUSTMENT }

/** Un movimiento detectado con baja confianza queda [PENDING] hasta que lo confirmes. */
enum class TransactionStatus { CONFIRMED, PENDING }

/** Clase de categoría: de gasto, de ingreso o neutra (transferencias, préstamos). */
enum class CategoryClass { EXPENSE, INCOME, NEUTRAL }

/** Resumen de una cuenta con su saldo calculado. */
data class AccountSummary(
    val id: Long,
    val name: String,
    val type: AccountType,
    val balance: Money,
)

/** Ingresos y gastos reales de un periodo. */
data class PeriodSummary(val income: Money, val expense: Money) {
    val net: Money get() = income - expense
}
