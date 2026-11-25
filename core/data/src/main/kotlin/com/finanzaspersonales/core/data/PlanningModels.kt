package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.finance.RateType
import java.time.Instant
import java.time.LocalDate

enum class DebtStatus { ACTIVE, PAID }

/** Cada cuánto se paga la cuota. Quincenal = dos cuotas al mes. */
enum class PaymentFrequency(val label: String, val perMonth: Int) {
    MONTHLY("Mensual", 1),
    BIWEEKLY("Quincenal", 2),
}

/** Moneda de un pago fijo. En USD el valor en pesos se estima con tu tasa. */
enum class PayCurrency { COP, USD }

/** Valor en dólares: `US$12,99`. [Money] guarda centavos de dólar. */
fun Money.formatUsd(): String = (if (isNegative) "-" else "") + "US$" + abs().formatCop(withSymbol = false)

/** Convierte centavos de dólar a pesos con [copPerUsd] (pesos por 1 dólar). */
fun Money.usdToCop(copPerUsd: Money): Money = Money((Math.multiplyExact(centavos, copPerUsd.centavos) + 50) / 100)

data class Debt(
    val id: Long,
    val name: String,
    val creditor: String?,
    val initial: Money,
    val balance: Money,
    val rateBasisPoints: Int,
    val rateType: RateType,
    /** Valor de cada cuota. */
    val installment: Money,
    val frequency: PaymentFrequency,
    /** Día de pago; si es quincenal, el primero de los dos (el segundo es 15 días después). */
    val dueDay: Int?,
    val remainingInstallments: Int?,
    val status: DebtStatus,
    val notes: String?,
) {
    /** Lo que pagas al mes en cuotas (quincenal = dos cuotas). */
    val monthlyPayment: Money get() = installment * frequency.perMonth

    /** Días del mes en que vence la cuota. */
    val dueDays: List<Int> get() {
        val first = dueDay ?: return emptyList()
        return if (frequency == PaymentFrequency.BIWEEKLY) listOf(first, if (first <= 15) first + 15 else first - 15).sorted() else listOf(first)
    }

    val paidPercent: Int get() = if (initial.centavos <= 0) 0 else (((initial - balance).centavos * 100) / initial.centavos).toInt().coerceIn(0, 100)
}

data class DebtPayment(val id: Long, val amount: Money, val balanceAfter: Money, val paidAt: Instant, val transactionId: Long?)

enum class GoalStatus { ACTIVE, ACHIEVED, PAUSED }

data class SavingsGoal(
    val id: Long,
    val name: String,
    val description: String?,
    val target: Money,
    val saved: Money,
    val deadline: LocalDate?,
    val isEmergencyFund: Boolean,
    val targetMonths: Int?,
    val customTarget: Boolean,
    val plannedMonthly: Money?,
    val status: GoalStatus,
)

data class GoalContribution(val id: Long, val amount: Money, val note: String?, val at: Instant)

data class Budget(val id: Long, val categoryId: Long, val categoryName: String, val isEssential: Boolean, val limit: Money)

data class Obligation(
    val id: Long,
    val name: String,
    val amount: Money,
    val dayOfMonth: Int,
    val isIncome: Boolean,
    val categoryId: Long?,
    val accountId: Long?,
    val active: Boolean,
    /** En USD, [amount] son centavos de dólar. */
    val currency: PayCurrency = PayCurrency.COP,
)

/** Resumen de tus últimos meses completos, base de todas las recomendaciones. */
data class FinancialProfile(
    val monthsAnalyzed: Int,
    val averageIncome: Money,
    val averageExpense: Money,
    val averageEssential: Money,
    /** Suma de las cuotas mensuales de las deudas activas. */
    val debtPayments: Money,
    /** Ingreso promedio menos gasto promedio menos cuotas de deudas. */
    val monthlySurplus: Money,
    /** true si los promedios salen de meses completos (y no solo del mes en curso). */
    val hasCompleteMonths: Boolean = false,
)
