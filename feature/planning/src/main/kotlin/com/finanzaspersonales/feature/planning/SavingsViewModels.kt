package com.finanzaspersonales.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.data.FinancialProfile
import com.finanzaspersonales.core.data.PlanningRepository
import com.finanzaspersonales.core.data.SavingsGoal
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.engine.finance.EmergencyFund
import com.finanzaspersonales.engine.finance.EmergencyPlan
import com.finanzaspersonales.engine.finance.GoalPlan
import com.finanzaspersonales.engine.finance.SavingsGoals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

private fun digits(s: String): Money = s.toLongOrNull()?.let { Money.ofPesos(it) } ?: Money.ZERO

data class GoalItem(val goal: SavingsGoal, val plan: GoalPlan)

data class GoalsState(
    val loading: Boolean = true,
    val goals: List<GoalItem> = emptyList(),
    val profile: FinancialProfile? = null,
    val today: LocalDate = LocalDate.now(),
)

@HiltViewModel
class GoalsViewModel @Inject constructor(private val planning: PlanningRepository, private val clock: Clock) : ViewModel() {
    val state: StateFlow<GoalsState> = combine(planning.observeGoals(), planning.observeProfile()) { goals, profile ->
        val today = BogotaTime.today(clock)
        GoalsState(
            loading = false,
            goals = goals.filter { !it.isEmergencyFund }.map {
                GoalItem(it, SavingsGoals.plan(it.target, it.saved, it.deadline, today, profile.monthlySurplus))
            },
            profile = profile,
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsState())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun save(id: Long?, name: String, description: String, targetDigits: String, deadline: LocalDate?, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { planning.saveGoal(id, name, description, digits(targetDigits), deadline, null) }
            .onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    /** [withdraw] = sacar dinero de la meta. */
    fun contribute(id: Long, amountDigits: String, withdraw: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val amount = digits(amountDigits)
            require(amount.centavos > 0) { "Escribe un valor" }
            planning.contribute(id, if (withdraw) -amount else amount, null)
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun delete(id: Long, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { planning.deleteGoal(id) }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }
}

data class EmergencyState(
    val loading: Boolean = true,
    val fund: SavingsGoal? = null,
    val profile: FinancialProfile? = null,
    val plan: EmergencyPlan? = null,
)

@HiltViewModel
class EmergencyFundViewModel @Inject constructor(private val planning: PlanningRepository, private val clock: Clock) : ViewModel() {
    init {
        viewModelScope.launch { planning.emergencyFundId() }
    }

    val state: StateFlow<EmergencyState> = combine(planning.observeGoals(), planning.observeProfile()) { goals, profile ->
        val fund = goals.firstOrNull { it.isEmergencyFund } ?: return@combine EmergencyState(loading = true)
        // Gastos esenciales del mes + cuotas de deudas: lo que tendrías que seguir pagando en una emergencia.
        val essential = profile.averageEssential + profile.debtPayments
        val plan = EmergencyFund.plan(
            essentialMonthly = essential,
            targetMonths = fund.targetMonths ?: 3,
            saved = fund.saved,
            monthlySurplus = profile.monthlySurplus,
            chosenMonthly = fund.plannedMonthly,
            today = BogotaTime.today(clock),
            customTarget = if (fund.customTarget) fund.target else null,
        )
        if (!fund.customTarget && plan.target != fund.target) viewModelScope.launch { planning.updateEmergencyTarget(plan.target) }
        EmergencyState(false, fund, profile, plan)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmergencyState())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun configure(months: Int, customTargetDigits: String?, plannedDigits: String, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            planning.configureEmergencyFund(
                targetMonths = months,
                customTarget = customTargetDigits?.takeIf { it.isNotBlank() }?.let { digits(it) },
                plannedMonthly = plannedDigits.takeIf { it.isNotBlank() }?.let { digits(it) },
            )
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }

    fun contribute(amountDigits: String, withdraw: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        runCatching {
            val amount = digits(amountDigits)
            require(amount.centavos > 0) { "Escribe un valor" }
            planning.contribute(planning.emergencyFundId(), if (withdraw) -amount else amount, null)
        }.onSuccess { onDone() }.onFailure { _message.value = it.message }
    }
}
