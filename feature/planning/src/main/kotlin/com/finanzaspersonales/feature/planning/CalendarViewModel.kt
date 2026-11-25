package com.finanzaspersonales.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.CashOverview
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.data.Obligation
import com.finanzaspersonales.core.data.OverviewRepository
import com.finanzaspersonales.core.data.PayCurrency
import com.finanzaspersonales.core.data.UserPreferences
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
    val currency: PayCurrency = PayCurrency.COP,
) {
    companion object {
        fun from(o: Obligation) = ObligationForm(
            o.id, o.name,
            if (o.currency == PayCurrency.USD) o.amount.toUsdText() else o.amount.toDigits(),
            o.dayOfMonth.toString(), o.isIncome, o.categoryId, o.accountId, o.active, o.currency,
        )
    }
}

/** "12,99" o "12.99" -> 1.299 centavos de dólar. */
internal fun parseUsd(text: String): Money? {
    val n = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (n.signum() <= 0 || n.scale() > 2) return null
    return runCatching { Money(n.movePointRight(2).longValueExact()) }.getOrNull()
}

internal fun Money.toUsdText(): String =
    java.math.BigDecimal(centavos).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ',')

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
    private val preferences: UserPreferences,
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
            val amount = if (form.currency == PayCurrency.USD) {
                requireNotNull(parseUsd(form.amountDigits)) { "Escribe el valor en dólares, por ejemplo 12,99" }
            } else {
                form.amountDigits.toLongOrNull()?.let { Money.ofPesos(it) } ?: Money.ZERO
            }
            val day = requireNotNull(form.day.toIntOrNull()) { "Escribe el día del mes (1 a 31)" }
            planning.saveObligation(form.id, form.name, amount, day, form.isIncome, form.categoryId, form.accountId, form.active, form.currency)
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    /**
     * [accountId] null = solo marcarlo, sin registrar un movimiento (por ejemplo si ya lo anotaste).
     * [paidDigits] = lo que realmente pagaste en pesos (vacío = el valor del pago fijo).
     * Si es un pago en dólares y [updateRate], la tasa se ajusta con lo que te cobraron.
     */
    fun markPaid(id: Long, accountId: Long?, paidDigits: String, usdAmount: Money?, updateRate: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val paid = paidDigits.toLongOrNull()?.takeIf { it > 0 }?.let { Money.ofPesos(it) }
            planning.markObligationPaid(id, month, accountId, paid)
            if (updateRate && paid != null && usdAmount != null && usdAmount.centavos > 0) {
                // Pesos cobrados / dólares = pesos por dólar (en centavos).
                preferences.setUsdRate(Money((paid.centavos * 100 + usdAmount.centavos / 2) / usdAmount.centavos))
            }
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    /** Abona solo una parte del pago o ingreso fijo de este mes. */
    fun payPart(id: Long, accountId: Long?, digits: String, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val amount = requireNotNull(digits.toLongOrNull()?.takeIf { it > 0 }) { "Escribe el valor" }
            planning.payObligationPart(id, month, accountId, Money.ofPesos(amount))
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun setUsdRate(digits: String, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val rate = requireNotNull(digits.toLongOrNull()?.takeIf { it > 0 }) { "Escribe cuántos pesos vale un dólar" }
            preferences.setUsdRate(Money.ofPesos(rate))
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun unmarkPaid(id: Long, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { planning.unmarkObligationPaid(id, month) }
            .onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun delete(id: Long, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { planning.deleteObligation(id) }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }
}
