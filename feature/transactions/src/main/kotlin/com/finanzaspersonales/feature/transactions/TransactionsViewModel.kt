package com.finanzaspersonales.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.data.TransactionRepository
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.Transaction
import com.finanzaspersonales.core.model.TransactionStatus
import com.finanzaspersonales.core.model.sum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class TransactionFilters(
    val period: Period = Period.MONTH,
    /** Cualquier fecha dentro del periodo mostrado; se mueve con anterior/siguiente. */
    val anchor: LocalDate,
    val accountId: Long? = null,
    val categoryId: Long? = null,
)

data class DayGroup(val date: LocalDate, val items: List<Transaction>)

data class TransactionsUiState(
    val loading: Boolean = true,
    val filters: TransactionFilters,
    val today: LocalDate,
    val groups: List<DayGroup> = emptyList(),
    val income: Money = Money.ZERO,
    val expense: Money = Money.ZERO,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
) {
    /** Solo se puede avanzar hasta el periodo actual, no al futuro. */
    val canGoForward: Boolean
        get() = filters.period.rangeContaining(filters.anchor).endExclusive <= filters.period.rangeContaining(today).start
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    private val today = BogotaTime.today(clock)
    private val filters = MutableStateFlow(TransactionFilters(anchor = today))

    private val list = filters.flatMapLatest { f ->
        transactions.observe(f.period.rangeContaining(f.anchor), f.accountId, f.categoryId)
    }

    val state: StateFlow<TransactionsUiState> = combine(
        filters,
        list,
        accounts.observeAccounts(includeArchived = true),
        categories.observeAll(),
    ) { f, items, accountList, categoryList ->
        val confirmed = items.filter { it.status == TransactionStatus.CONFIRMED }
        TransactionsUiState(
            loading = false,
            filters = f,
            today = today,
            groups = items.groupBy { BogotaTime.dateOf(it.occurredAt) }.map { (d, l) -> DayGroup(d, l) },
            income = confirmed.filter { it.kind.countsAsIncome }.map { it.amount }.sum(),
            // Gastos reales: gastos y comisiones, menos devoluciones.
            expense = -(confirmed.filter { it.kind.countsAsExpense || it.kind == com.finanzaspersonales.core.model.TransactionKind.REFUND }
                .map { it.amount }.sum()),
            accounts = accountList,
            categories = categoryList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState(filters = filters.value, today = today))

    fun setPeriod(period: Period) = filters.update { it.copy(period = period, anchor = today) }
    fun setAccount(id: Long?) = filters.update { it.copy(accountId = id) }
    fun setCategory(id: Long?) = filters.update { it.copy(categoryId = id) }

    fun previous() = filters.update { it.copy(anchor = shift(it, -1)) }
    fun next() = filters.update { it.copy(anchor = shift(it, 1)) }

    private fun shift(f: TransactionFilters, step: Long): LocalDate = when (f.period) {
        Period.DAY -> f.anchor.plusDays(step)
        Period.WEEK -> f.anchor.plusWeeks(step)
        Period.MONTH -> f.anchor.plusMonths(step)
        Period.YEAR -> f.anchor.plusYears(step)
    }
}
