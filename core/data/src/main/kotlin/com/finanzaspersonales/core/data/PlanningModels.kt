package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.engine.finance.RateType
import java.time.Instant
import java.time.LocalDate

enum class DebtStatus { ACTIVE, PAID }

data class Debt(
    val id: Long,
    val name: String,
    val creditor: String?,
    val initial: Money,
    val balance: Money,
    val rateBasisPoints: Int,
    val rateType: RateType,
    val monthlyPayment: Money,
    val dueDay: Int?,
    val remainingInstallments: Int?,
    val status: DebtStatus,
    val notes: String?,
) {
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
)
