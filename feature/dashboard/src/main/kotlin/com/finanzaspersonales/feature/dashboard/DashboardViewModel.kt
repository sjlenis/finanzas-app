package com.finanzaspersonales.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.FinanceRepository
import com.finanzaspersonales.core.model.AccountSummary
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.PeriodSummary
import com.finanzaspersonales.core.model.sum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardUiState(
    val loading: Boolean = true,
    val accounts: List<AccountSummary> = emptyList(),
    val month: PeriodSummary = PeriodSummary(Money.ZERO, Money.ZERO),
) {
    val total: Money get() = accounts.map { it.balance }.sum()
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: FinanceRepository,
) : ViewModel() {

    val state: StateFlow<DashboardUiState> =
        combine(repository.observeAccounts(), repository.observeCurrentMonth()) { accounts, month ->
            DashboardUiState(loading = false, accounts = accounts, month = month)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
