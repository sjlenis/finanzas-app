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
) {
    /** Dinero en cuentas líquidas (las que cuentan como disponibles). */
    val total: Money get() = accounts.filter { it.isLiquid }.map { it.balance }.sum()
    val cash: Money get() = accounts.filter { it.type == AccountType.CASH }.map { it.balance }.sum()
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    finance: FinanceRepository,
    accounts: AccountRepository,
    transactions: TransactionRepository,
    security: SecuritySettings,
    clock: Clock,
) : ViewModel() {

    private val month = Period.MONTH.rangeContaining(BogotaTime.today(clock))

    val state: StateFlow<DashboardUiState> = combine(
        accounts.observeAccounts(),
        finance.observeCurrentMonth(),
        transactions.observeSpendingByCategory(month),
        security.config.map { it.hasPin },
        transactions.observePendingCount(),
    ) { accountList, summary, spending, hasPin, pending ->
        DashboardUiState(
            loading = false, accounts = accountList, month = summary, spending = spending, hasPin = hasPin, pendingCount = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
