package com.finanzaspersonales.feature.planning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.Debt
import com.finanzaspersonales.core.data.DebtPayment
import com.finanzaspersonales.core.data.DebtStatus
import com.finanzaspersonales.core.data.FinancialProfile
import com.finanzaspersonales.core.data.PlanningRepository
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.sum
import com.finanzaspersonales.engine.finance.DebtComparison
import com.finanzaspersonales.engine.finance.DebtInput
import com.finanzaspersonales.engine.finance.DebtPlanner
import com.finanzaspersonales.engine.finance.RateType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** Datos del formulario de una deuda. */
data class DebtForm(
    val name: String = "",
    val creditor: String = "",
    val initialDigits: String = "",
    val balanceDigits: String = "",
    val rateText: String = "",
    val rateType: RateType = RateType.EFFECTIVE_ANNUAL,
    val paymentDigits: String = "",
    val dueDay: String = "",
    val installments: String = "",
    val notes: String = "",
) {
    /** "25,5" o "25.5" -> 2550 centésimas de punto. */
    val rateBasisPoints: Int?
        get() {
            if (rateType == RateType.NONE || rateText.isBlank()) return 0
            val n = rateText.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
            return n.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toInt().takeIf { it in 0..100_000 }
        }

    companion object {
        fun from(d: Debt) = DebtForm(
            name = d.name, creditor = d.creditor.orEmpty(), initialDigits = d.initial.toDigits(), balanceDigits = d.balance.toDigits(),
            rateText = if (d.rateBasisPoints == 0) "" else java.math.BigDecimal(d.rateBasisPoints).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ','),
            rateType = d.rateType, paymentDigits = d.monthlyPayment.toDigits(), dueDay = d.dueDay?.toString().orEmpty(),
            installments = d.remainingInstallments?.toString().orEmpty(), notes = d.notes.orEmpty(),
        )
    }
}

private fun digits(s: String): Money = s.toLongOrNull()?.let { Money.ofPesos(it) } ?: Money.ZERO

data class DebtsState(val loading: Boolean = true, val debts: List<Debt> = emptyList()) {
    val active: List<Debt> get() = debts.filter { it.status == DebtStatus.ACTIVE }
    val totalBalance: Money get() = active.map { it.balance }.sum()
    val totalMonthly: Money get() = active.map { it.monthlyPayment }.sum()
}

@HiltViewModel
class DebtsViewModel @Inject constructor(private val planning: PlanningRepository) : ViewModel() {
    val state: StateFlow<DebtsState> = planning.observeDebts().map { DebtsState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtsState())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun save(id: Long?, f: DebtForm, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val rate = requireNotNull(f.rateBasisPoints) { "Revisa la tasa de interés" }
            val initial = digits(f.initialDigits)
            planning.saveDebt(
                id = id, name = f.name, creditor = f.creditor, initial = initial,
                balance = if (f.balanceDigits.isBlank()) initial else digits(f.balanceDigits),
                rateBasisPoints = rate, rateType = if (rate == 0) RateType.NONE else f.rateType,
                monthlyPayment = digits(f.paymentDigits), dueDay = f.dueDay.toIntOrNull(),
                remainingInstallments = f.installments.toIntOrNull(), notes = f.notes,
            )
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }
}

data class DebtDetailState(
    val debt: Debt? = null,
    val payments: List<DebtPayment> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val today: LocalDate = LocalDate.now(),
)

@HiltViewModel
class DebtDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val planning: PlanningRepository,
    accounts: AccountRepository,
    private val clock: Clock,
) : ViewModel() {
    private val id: Long = checkNotNull(savedStateHandle[ARG_DEBT_ID])

    val state: StateFlow<DebtDetailState> = combine(
        planning.observeDebt(id), planning.observeDebtPayments(id), accounts.observeAccounts(),
    ) { d, p, a -> DebtDetailState(d, p, a, BogotaTime.today(clock)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtDetailState())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun pay(amountDigits: String, accountId: Long?, newBalanceDigits: String, date: LocalDate, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            planning.registerDebtPayment(
                debtId = id, amount = digits(amountDigits), accountId = accountId,
                newBalance = newBalanceDigits.takeIf { it.isNotBlank() }?.let { digits(it) }, date = date,
            )
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun save(f: DebtForm, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val rate = requireNotNull(f.rateBasisPoints) { "Revisa la tasa de interés" }
            planning.saveDebt(
                id = id, name = f.name, creditor = f.creditor, initial = digits(f.initialDigits), balance = digits(f.balanceDigits),
                rateBasisPoints = rate, rateType = if (rate == 0) RateType.NONE else f.rateType,
                monthlyPayment = digits(f.paymentDigits), dueDay = f.dueDay.toIntOrNull(),
                remainingInstallments = f.installments.toIntOrNull(), notes = f.notes,
            )
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        runCatching { planning.deleteDebt(id) }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    companion object { const val ARG_DEBT_ID = "debtId" }
}

data class DebtPlanState(
    val loading: Boolean = true,
    val extraDigits: String = "",
    val comparison: DebtComparison? = null,
    val profile: FinancialProfile? = null,
    val hasDebts: Boolean = false,
) {
    /** Lo que te sobra al mes antes de pagar deudas (ingresos - gastos). */
    val surplusBeforeDebts: Money? get() = profile?.let { it.averageIncome - it.averageExpense }
}

@HiltViewModel
class DebtPlanViewModel @Inject constructor(planning: PlanningRepository, private val clock: Clock) : ViewModel() {
    private val extra = MutableStateFlow("")

    val state: StateFlow<DebtPlanState> = combine(planning.observeDebts(), planning.observeProfile(), extra) { debts, profile, extraDigits ->
        val inputs = debts.filter { it.status == DebtStatus.ACTIVE }.map {
            DebtInput(it.id, it.name, it.balance, it.rateBasisPoints, it.rateType, it.monthlyPayment)
        }
        DebtPlanState(
            loading = false,
            extraDigits = extraDigits,
            comparison = if (inputs.isEmpty()) null else DebtPlanner.compare(inputs, digits(extraDigits), YearMonth.from(BogotaTime.today(clock)).plusMonths(1)),
            profile = profile,
            hasDebts = inputs.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtPlanState())

    fun setExtra(digits: String) { extra.value = digits }
}
