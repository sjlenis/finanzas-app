package com.finanzaspersonales.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.InsightsReport
import com.finanzaspersonales.core.data.InsightsRepository
import com.finanzaspersonales.core.data.OverviewRepository
import com.finanzaspersonales.core.data.PlanningRepository
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.engine.finance.PurchaseAnalysis
import com.finanzaspersonales.engine.finance.RecurringExpense
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(
    overview: OverviewRepository,
    private val insights: InsightsRepository,
    private val planning: PlanningRepository,
) : ViewModel() {

    /** Se recalcula cada vez que cambian tus cuentas, pagos o presupuestos. */
    val report: StateFlow<InsightsReport?> = overview.observe().mapLatest { insights.report() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _purchase = MutableStateFlow<PurchaseAnalysis?>(null)
    val purchase = _purchase.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun simulate(priceDigits: String) = viewModelScope.launch {
        runCatching {
            val price = requireNotNull(priceDigits.toLongOrNull()?.takeIf { it > 0 }) { "Escribe el valor de lo que quieres comprar" }
            insights.simulatePurchase(Money.ofPesos(price))
        }.onSuccess { _purchase.value = it }.onFailure { _message.value = it.message }
    }

    fun clearPurchase() { _purchase.value = null }

    /** Agrega un pago recurrente detectado al calendario como pago fijo. */
    fun addToCalendar(r: RecurringExpense) = viewModelScope.launch {
        runCatching { planning.saveObligation(null, r.name, r.averageAmount, r.typicalDay, false, null, null, true) }
            .onSuccess { _message.value = "Agregué «${r.name}» al calendario de pagos. Puedes ajustarlo allí." }
            .onFailure { _message.value = it.message }
    }
}
