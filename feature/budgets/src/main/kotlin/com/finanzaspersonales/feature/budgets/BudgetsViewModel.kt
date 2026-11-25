package com.finanzaspersonales.feature.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.CashOverview
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.data.OverviewRepository
import com.finanzaspersonales.core.data.PlanningRepository
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.CategoryClass
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.engine.finance.BudgetAdvisor
import com.finanzaspersonales.engine.finance.BudgetRecommendation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BudgetsState(
    val loading: Boolean = true,
    val overview: CashOverview? = null,
    /** Categorías principales de gasto (las que pueden tener presupuesto). */
    val categories: List<Category> = emptyList(),
)

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    overview: OverviewRepository,
    categories: CategoryRepository,
    private val planning: PlanningRepository,
) : ViewModel() {

    val state: StateFlow<BudgetsState> = combine(overview.observe(), categories.observeAll()) { o, c ->
        BudgetsState(false, o, c.filter { it.parentId == null && it.categoryClass == CategoryClass.EXPENSE })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsState())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private val _recommendation = MutableStateFlow<BudgetRecommendation?>(null)
    val recommendation = _recommendation.asStateFlow()

    fun setBudget(categoryId: Long, digits: String, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val amount = digits.toLongOrNull()?.let { Money.ofPesos(it) } ?: Money.ZERO
            planning.setBudget(categoryId, amount)
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun deleteBudget(id: Long) = viewModelScope.launch { planning.deleteBudget(id) }

    /** Sugerencias según tu gasto real de los últimos meses, tu ingreso y tus cuotas. */
    fun suggest() = viewModelScope.launch {
        val o = state.value.overview ?: return@launch
        val history = planning.categoryHistory()
        val income = maxOf(o.profile.averageIncome, o.monthItems.filter { it.type == com.finanzaspersonales.core.data.UpcomingType.INCOME }.map { it.amount }.fold(Money.ZERO) { a, b -> a + b })
        _recommendation.value = BudgetAdvisor.recommend(history, income, o.profile.debtPayments)
    }

    fun dismissSuggestions() { _recommendation.value = null }

    fun applySuggestions() = viewModelScope.launch {
        val r = _recommendation.value ?: return@launch
        runCatching { r.suggestions.filter { it.suggested.centavos > 0 }.forEach { planning.setBudget(it.categoryId, it.suggested) } }
            .onFailure { _message.value = it.message }
        _recommendation.value = null
    }
}
