package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.ClosedOpenRange
import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionStatus
import com.finanzaspersonales.engine.finance.Advisor
import com.finanzaspersonales.engine.finance.AdvisorInput
import com.finanzaspersonales.engine.finance.AntExpenses
import com.finanzaspersonales.engine.finance.BudgetAlert
import com.finanzaspersonales.engine.finance.BudgetLevel
import com.finanzaspersonales.engine.finance.CategoryTrend
import com.finanzaspersonales.engine.finance.DebtSummary
import com.finanzaspersonales.engine.finance.Expense
import com.finanzaspersonales.engine.finance.Insight
import com.finanzaspersonales.engine.finance.PurchaseAnalysis
import com.finanzaspersonales.engine.finance.PurchaseSimulator
import com.finanzaspersonales.engine.finance.RecurringExpense
import com.finanzaspersonales.engine.finance.SpendingPatterns
import com.finanzaspersonales.engine.finance.UnusualExpense
import com.finanzaspersonales.engine.finance.UpcomingPayment
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado del análisis de hábitos, listo para mostrar. */
data class InsightsReport(
    val today: LocalDate,
    /** Meses completos con movimientos que se usaron como base. */
    val monthsAnalyzed: Int,
    val expensesAnalyzed: Int,
    val insights: List<Insight>,
    val trends: List<CategoryTrend>,
    val ants: AntExpenses?,
    val recurring: List<RecurringExpense>,
    val unusual: List<UnusualExpense>,
)

/**
 * Asesor financiero: junta tus movimientos, presupuestos, deudas y metas y los pasa por
 * las reglas del motor financiero. Todo ocurre en el teléfono, sin enviar datos a nadie.
 */
@Singleton
class InsightsRepository @Inject constructor(
    private val transactions: TransactionRepository,
    private val planning: PlanningRepository,
    private val overview: OverviewRepository,
    private val clock: Clock,
) {
    /** Gastos reales de los últimos [months] meses (incluido el actual). */
    suspend fun expenses(months: Int = 6): List<Expense> {
        val today = BogotaTime.today(clock)
        val from = YearMonth.from(today).minusMonths(months.toLong() - 1).atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant()
        val range = ClosedOpenRange(from, Period.DAY.rangeContaining(today).endExclusive)
        return transactions.observe(range).first()
            .filter { it.status == TransactionStatus.CONFIRMED && it.kind.countsAsExpense && it.amount.isNegative }
            .map { Expense(it.id, BogotaTime.dateOf(it.occurredAt), it.amount.abs(), it.categoryId, it.categoryName, it.description) }
    }

    suspend fun report(): InsightsReport {
        val today = BogotaTime.today(clock)
        val o = overview.observe().first()
        val expenses = expenses()
        val history = planning.categoryHistory()
        val thisMonth = transactions.spendingByCategory(Period.MONTH.rangeContaining(today))
            .mapNotNull { c -> c.categoryId?.let { it to c.amount } }.toMap()
        // Solo comparamos contra meses completos: si aún no hay, no hay tendencia.
        val trends = if (history.isNotEmpty() && o.profile.hasCompleteMonths) {
            SpendingPatterns.trends(
                thisMonth,
                history.associate { it.categoryId to it.monthlyAverage },
                history.associate { it.categoryId to (it.name to it.essential) },
                today,
            )
        } else emptyList()
        val ants = SpendingPatterns.antExpenses(expenses, today)
        val unusual = SpendingPatterns.unusual(expenses, today)
        val obligationKeys = planning.observeObligations().first().map { SpendingPatterns.key(it.name) }.toSet()
        val recurring = SpendingPatterns.recurring(expenses, today)
        val debts = planning.observeDebts().first().filter { it.status == DebtStatus.ACTIVE }
        val fund = planning.observeGoals().first().firstOrNull { it.isEmergencyFund }
        val input = AdvisorInput(
            today = today,
            monthsAnalyzed = o.profile.monthsAnalyzed,
            available = o.money.available,
            projectedEndOfMonth = o.projectedEndOfMonth,
            monthlySurplus = o.profile.monthlySurplus,
            averageEssential = o.profile.averageEssential,
            budgetsOver = o.budgets.filter { it.status.level == BudgetLevel.OVER }.map { BudgetAlert(it.budget.categoryName, it.status.spent, it.status.limit) },
            budgetsNear = o.budgets.filter { it.status.level == BudgetLevel.NEAR }.map { BudgetAlert(it.budget.categoryName, it.status.spent, it.status.limit) },
            trends = trends,
            upcoming = o.pending.map { UpcomingPayment(it.name, it.amount, it.date) },
            ants = ants,
            unusual = unusual,
            recurringNotInCalendar = recurring.filter { SpendingPatterns.key(it.name) !in obligationKeys },
            debts = debts.map { DebtSummary(it.name, it.balance, it.rateBasisPoints, it.rateType) },
            emergencySaved = fund?.saved ?: Money.ZERO,
            emergencyTarget = fund?.target ?: Money.ZERO,
            nonEssentialAverages = history.filter { !it.essential }.map { it.name to it.monthlyAverage },
        )
        return InsightsReport(today, o.profile.monthsAnalyzed, expenses.size, Advisor.build(input), trends, ants, recurring, unusual)
    }

    /** ¿Puedo comprar algo de [price]? Usa tu dinero realmente disponible y tu gasto diario. */
    suspend fun simulatePurchase(price: Money): PurchaseAnalysis {
        val o = overview.observe().first()
        return PurchaseSimulator.analyze(
            price = price,
            available = o.money.available,
            dailyVariableSpending = o.dailyVariableSpending,
            daysUntilIncome = o.daysUntilIncome,
            monthlySurplus = o.profile.monthlySurplus,
            emergencySaved = o.money.emergencyFund,
        )
    }
}
