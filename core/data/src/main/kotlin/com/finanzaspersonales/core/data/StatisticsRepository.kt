package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.model.CategorySpending
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.sum
import com.finanzaspersonales.engine.finance.Statistics
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Ingresos y gastos reales de un mes (sin transferencias propias ni préstamos). */
data class MonthTotals(val month: YearMonth, val income: Money, val expense: Money) {
    val net: Money get() = income - expense
}

data class CategoryComparison(val name: String, val thisMonth: Money, val lastMonth: Money) {
    val change: Money get() = thisMonth - lastMonth
}

/** Patrimonio neto: lo que tienes (saldos de todas tus cuentas) menos lo que debes. */
data class NetWorth(val accounts: List<Pair<String, Money>>, val debts: List<Pair<String, Money>>) {
    val assets: Money get() = accounts.map { it.second }.sum()
    val liabilities: Money get() = debts.map { it.second }.sum()
    val net: Money get() = assets - liabilities
}

data class StatsReport(
    val months: List<MonthTotals>,
    /** Ahorrado en metas y fondo de emergencia al final de cada mes. */
    val savings: List<Pair<YearMonth, Money>>,
    /** Total adeudado al final de cada mes, según los pagos registrados. */
    val debt: List<Pair<YearMonth, Money>>,
    val categoriesThisMonth: List<CategorySpending>,
    val comparison: List<CategoryComparison>,
    val netWorth: NetWorth,
    val hasDebts: Boolean,
    val hasSavings: Boolean,
)

@Singleton
class StatisticsRepository @Inject constructor(
    private val db: FinanzasDatabase,
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
    private val clock: Clock,
) {
    suspend fun report(monthCount: Int = 6): StatsReport {
        val today = BogotaTime.today(clock)
        val months = Statistics.lastMonths(today, monthCount)
        val dao = db.planningDao()
        val from = months.first().atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant().toEpochMilli()
        val to = months.last().plusMonths(1).atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant().toEpochMilli()
        val totals = dao.observeMonthlyTotals(from, to, PlanningRepository.BOGOTA_OFFSET_SECONDS).first().associateBy { it.month }
        val monthTotals = months.map { m ->
            val row = totals[m.toString()]
            MonthTotals(m, Money(row?.incomeCentavos ?: 0), Money(row?.expenseCentavos ?: 0))
        }

        val contributions = dao.getAllContributions().map { BogotaTime.dateOf(Instant.ofEpochMilli(it.createdAtEpochMs)) to Money(it.amountCentavos) }
        val savings = months.zip(Statistics.cumulativeByMonth(contributions, months))

        val payments = dao.getAllDebtPayments().groupBy { it.debtId }
        val debtEntities = dao.getAllDebts()
        val histories = debtEntities.map { d ->
            val list = payments[d.id].orEmpty().sortedBy { it.paidAtEpochMs }
            val first = list.firstOrNull()
            Statistics.DebtHistory(
                createdOn = BogotaTime.dateOf(Instant.ofEpochMilli(d.createdAtEpochMs)),
                startingBalance = Money(first?.let { it.balanceAfterCentavos + it.amountCentavos } ?: d.balanceCentavos),
                payments = list.map { BogotaTime.dateOf(Instant.ofEpochMilli(it.paidAtEpochMs)) to Money(it.balanceAfterCentavos) },
            )
        }
        val debt = months.zip(Statistics.debtByMonth(histories, months))

        val thisMonth = transactions.spendingByCategory(Period.MONTH.rangeContaining(today))
        val lastMonth = transactions.spendingByCategory(Period.MONTH.rangeContaining(today.minusMonths(1)))
        val lastByName = lastMonth.associateBy { it.categoryName }
        val comparison = (thisMonth.map { it.categoryName } + lastMonth.map { it.categoryName }).distinct().map { name ->
            CategoryComparison(name, thisMonth.firstOrNull { it.categoryName == name }?.amount ?: Money.ZERO, lastByName[name]?.amount ?: Money.ZERO)
        }.sortedByDescending { maxOf(it.thisMonth.centavos, it.lastMonth.centavos) }

        val netWorth = NetWorth(
            accounts = accounts.observeAccounts(includeArchived = true).first().filter { !it.isArchived || !it.balance.isZero }.map { it.name to it.balance },
            debts = debtEntities.filter { it.status == DebtStatus.ACTIVE.name && it.balanceCentavos > 0 }.map { it.name to Money(it.balanceCentavos) },
        )
        return StatsReport(
            months = monthTotals,
            savings = savings,
            debt = debt,
            categoriesThisMonth = thisMonth,
            comparison = comparison,
            netWorth = netWorth,
            hasDebts = debtEntities.isNotEmpty(),
            hasSavings = contributions.isNotEmpty(),
        )
    }
}
