package com.finanzaspersonales.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.FinanceRepository
import com.finanzaspersonales.core.data.TransactionRepository
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.CategorySpending
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.PeriodSummary
import com.finanzaspersonales.core.model.sum
import com.finanzaspersonales.core.security.SecuritySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import javax.inject.Inject

data class DashboardUiState(
    val loading: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val month: PeriodSummary = PeriodSummary(Money.ZERO, Money.ZERO),
    val spending: List<CategorySpending> = emptyList(),
    val hasPin: Boolean = true,
    val pendingCount: Int = 0,
    val debtTotal: Money = Money.ZERO,
    val savedInGoals: Money = Money.ZERO,
    val emergencySaved: Money = Money.ZERO,
    val emergencyTarget: Money = Money.ZERO,
    val hasPlanning: Boolean = false,
    /** Dinero realmente disponible, pagos del mes y presupuestos. */
    val overview: com.finanzaspersonales.core.data.CashOverview? = null,
    /** Las recomendaciones más importantes del asesor. */
    val insights: List<com.finanzaspersonales.engine.finance.Insight> = emptyList(),
    /** Ingresos y gastos de los últimos meses para la gráfica de Inicio. */
    val months: List<com.finanzaspersonales.core.data.MonthTotals> = emptyList(),
) {
    /** Dinero en cuentas líquidas (las que cuentan como disponibles). */
    val total: Money get() = accounts.filter { it.isLiquid }.map { it.balance }.sum()
    val cash: Money get() = accounts.filter { it.type == AccountType.CASH }.map { it.balance }.sum()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    finance: FinanceRepository,
    accounts: AccountRepository,
    transactions: TransactionRepository,
    planning: com.finanzaspersonales.core.data.PlanningRepository,
    overview: com.finanzaspersonales.core.data.OverviewRepository,
    insights: com.finanzaspersonales.core.data.InsightsRepository,
    stats: com.finanzaspersonales.core.data.StatisticsRepository,
    security: SecuritySettings,
    clock: Clock,
) : ViewModel() {

    private val month = Period.MONTH.rangeContaining(BogotaTime.today(clock))

    private val base = combine(
        accounts.observeAccounts(),
        finance.observeCurrentMonth(),
        transactions.observeSpendingByCategory(month),
        security.config.map { it.hasPin },
        transactions.observePendingCount(),
    ) { accountList, summary, spending, hasPin, pending ->
        DashboardUiState(
            loading = false, accounts = accountList, month = summary, spending = spending, hasPin = hasPin, pendingCount = pending,
        )
    }

    val state: StateFlow<DashboardUiState> = combine(
        base, planning.observeDebts(), planning.observeGoals(), overview.observe(),
        overview.observe().mapLatest {
            runCatching { insights.report().insights }.getOrDefault(emptyList()) to
                runCatching { stats.report().months }.getOrDefault(emptyList())
        },
    ) { s, debts, goals, o, (tips, months) ->
        val fund = goals.firstOrNull { it.isEmergencyFund }
        s.copy(
            debtTotal = debts.filter { it.status == com.finanzaspersonales.core.data.DebtStatus.ACTIVE }.map { it.balance }.sum(),
            savedInGoals = goals.filter { !it.isEmergencyFund }.map { it.saved }.sum(),
            emergencySaved = fund?.saved ?: Money.ZERO,
            emergencyTarget = fund?.target ?: Money.ZERO,
            hasPlanning = debts.isNotEmpty() || goals.isNotEmpty(),
            overview = o,
            insights = tips,
            months = months,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
