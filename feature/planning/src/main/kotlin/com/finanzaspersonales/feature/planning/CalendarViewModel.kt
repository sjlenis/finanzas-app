package com.finanzaspersonales.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.CashOverview
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.data.Obligation
import com.finanzaspersonales.core.data.OverviewRepository
import com.finanzaspersonales.core.data.PlanningRepository
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

data class ObligationForm(
    val id: Long? = null,
    val name: String = "",
    val amountDigits: String = "",
    val day: String = "",
    val isIncome: Boolean = false,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val active: Boolean = true,
) {
    companion object {
        fun from(o: Obligation) = ObligationForm(o.id, o.name, o.amount.toDigits(), o.dayOfMonth.toString(), o.isIncome, o.categoryId, o.accountId, o.active)
    }
}

data class CalendarState(
    val loading: Boolean = true,
    val overview: CashOverview? = null,
    val obligations: List<Obligation> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    overview: OverviewRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val planning: PlanningRepository,
) : ViewModel() {

    val state: StateFlow<CalendarState> = combine(
        overview.observe(), planning.observeObligations(), accounts.observeAccounts(), categories.observeAll(),
    ) { o, obligations, accountList, categoryList ->
        CalendarState(false, o, obligations, accountList, categoryList)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarState())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private val month: YearMonth get() = state.value.overview?.today?.let { YearMonth.from(it) } ?: YearMonth.now()

    fun save(form: ObligationForm, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val amount = form.amountDigits.toLongOrNull()?.let { Money.ofPesos(it) } ?: Money.ZERO
            val day = requireNotNull(form.day.toIntOrNull()) { "Escribe el día del mes (1 a 31)" }
            planning.saveObligation(form.id, form.name, amount, day, form.isIncome, form.categoryId, form.accountId, form.active)
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    /** [accountId] null = solo marcarlo, sin registrar un movimiento (por ejemplo si ya lo anotaste). */
    fun markPaid(id: Long, accountId: Long?, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { planning.markObligationPaid(id, month, accountId) }
            .onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun unmarkPaid(id: Long, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { planning.unmarkObligationPaid(id, month) }
            .onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun delete(id: Long, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { planning.deleteObligation(id) }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }
}
