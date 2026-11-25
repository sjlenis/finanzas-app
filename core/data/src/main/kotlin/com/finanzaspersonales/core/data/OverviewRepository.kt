package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.sum
import com.finanzaspersonales.engine.finance.AvailableMoney
import com.finanzaspersonales.engine.finance.BudgetStatus
import com.finanzaspersonales.engine.finance.Projection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class UpcomingType { OBLIGATION, DEBT, INCOME }

/** Un pago (o ingreso) previsto en el calendario. */
data class UpcomingItem(val id: Long, val type: UpcomingType, val name: String, val amount: Money, val date: LocalDate, val paid: Boolean)

data class BudgetLine(val budget: Budget, val status: BudgetStatus)

/**
 * Foto del mes en curso: dinero realmente disponible, pagos pendientes, ingresos
 * previstos, presupuestos y proyección. Nada se resta dos veces: un pago ya hecho
 * salió de la cuenta y deja de estar "pendiente"; las metas y el fondo son dinero
 * apartado que sigue en las cuentas.
 */
data class CashOverview(
    val today: LocalDate,
    val money: AvailableMoney,
    /** Pagos del mes (pagados y pendientes), en orden de fecha. */
    val monthItems: List<UpcomingItem>,
    val expectedIncome: Money,
    /** Fecha del próximo ingreso previsto, si hay. */
    val nextIncome: LocalDate?,
    val weeklyAllowance: Money,
    val weeksUntilHorizon: Int,
    val projectedEndOfMonth: Money,
    val budgets: List<BudgetLine>,
    val profile: FinancialProfile,
) {
    val pending: List<UpcomingItem> get() = monthItems.filter { !it.paid && it.type != UpcomingType.INCOME }
}

@Singleton
class OverviewRepository @Inject constructor(
    private val accounts: AccountRepository,
    private val planning: PlanningRepository,
    private val transactions: TransactionRepository,
    private val clock: Clock,
) {
    fun observe(): Flow<CashOverview> {
        val today = BogotaTime.today(clock)
        val month = YearMonth.from(today)
        val monthRange = Period.MONTH.rangeContaining(today)
        val debtsThisMonth = planning.observeDebtsPaidIn(month)
        val schedule = combine(
            planning.observeObligations(), planning.observePaidObligations(month), planning.observeDebts(), debtsThisMonth,
        ) { obligations, paid, debts, paidDebts ->
            buildList {
                obligations.filter { it.active }.forEach { o ->
                    add(UpcomingItem(o.id, if (o.isIncome) UpcomingType.INCOME else UpcomingType.OBLIGATION, o.name, o.amount, month.dayClamped(o.dayOfMonth), o.id in paid))
                }
                debts.filter { it.status == DebtStatus.ACTIVE && it.monthlyPayment.centavos > 0 }.forEach { d ->
                    add(UpcomingItem(d.id, UpcomingType.DEBT, d.name, minOf(d.monthlyPayment, d.balance), month.dayClamped(d.dueDay ?: month.lengthOfMonth()), d.id in paidDebts))
                }
            }.sortedBy { it.date }
        }
        val budgets = combine(planning.observeBudgets(), transactions.observeSpendingByCategory(monthRange)) { list, spending ->
            val byCategory = spending.associate { it.categoryId to it.amount }
            list.map { BudgetLine(it, BudgetStatus(it.limit, byCategory[it.categoryId] ?: Money.ZERO)) }
        }
        return combine(accounts.observeAccounts(), planning.observeGoals(), schedule, budgets, planning.observeProfile()) { accountList, goals, items, budgetLines, profile ->
            val liquid = accountList.filter { it.isLiquid }.map { it.balance }.sum()
            val pending = items.filter { !it.paid && it.type != UpcomingType.INCOME }
            val incomes = items.filter { it.type == UpcomingType.INCOME }
            val expectedIncome = incomes.filter { !it.paid }.map { it.amount }.sum()
            val money = AvailableMoney(
                total = liquid,
                obligations = pending.map { it.amount }.sum(),
                emergencyFund = goals.filter { it.isEmergencyFund }.map { it.saved }.sum(),
                goals = goals.filter { !it.isEmergencyFund }.map { it.saved }.sum(),
            )
            val nextIncome = incomes.filter { !it.paid && !it.date.isBefore(today) }.minOfOrNull { it.date }
                ?: incomes.minOfOrNull { it.date.plusMonths(1) }
            val horizon = nextIncome ?: month.atEndOfMonth()
            val weeks = ((ChronoUnit.DAYS.between(today, horizon) + 7) / 7).toInt().coerceAtLeast(1)
            val weekly = if (money.available.centavos <= 0) Money.ZERO else Money(money.available.centavos / weeks)
            // Gasto variable diario: gasto promedio sin los pagos fijos (que ya están en "pendientes").
            val fixed = items.filter { it.type == UpcomingType.OBLIGATION }.map { it.amount }.sum()
            val variableMonthly = (profile.averageExpense - fixed).let { if (it.isNegative) Money.ZERO else it }
            val daily = Money(variableMonthly.centavos / 30)
            CashOverview(
                today = today,
                money = money,
                monthItems = items,
                expectedIncome = expectedIncome,
                nextIncome = nextIncome,
                weeklyAllowance = weekly,
                weeksUntilHorizon = weeks,
                projectedEndOfMonth = Projection.endOfMonth(liquid, expectedIncome, money.obligations, daily, today),
                budgets = budgetLines.sortedByDescending { it.status.percent },
                profile = profile,
            )
        }
    }

    private fun YearMonth.dayClamped(day: Int): LocalDate = atDay(day.coerceIn(1, lengthOfMonth()))
}
