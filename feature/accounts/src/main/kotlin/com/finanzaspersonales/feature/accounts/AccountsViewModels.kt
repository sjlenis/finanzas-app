package com.finanzaspersonales.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.ClosedOpenRange
import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.TransactionRepository
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.AccountActivity
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.Transaction
import com.finanzaspersonales.core.model.formatCop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

fun AccountType.label(): String = when (this) {
    AccountType.BANK -> "Cuenta bancaria"
    AccountType.WALLET -> "Billetera digital"
    AccountType.CASH -> "Efectivo"
    AccountType.OTHER -> "Otra"
}

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: AccountRepository,
) : ViewModel() {
    val accounts: StateFlow<List<Account>?> =
        repository.observeAccounts(includeArchived = true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun create(name: String, type: AccountType, initial: Money, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                require(name.isNotBlank()) { "Escribe un nombre" }
                repository.create(name, type, institution = null, initialBalance = initial)
            }.onSuccess { onDone() }.onFailure { _message.value = it.message }
        }
    }

    fun clearMessage() { _message.value = null }
}

data class AccountDetailState(
    val account: Account? = null,
    val monthActivity: AccountActivity? = null,
    val history: List<Transaction> = emptyList(),
)

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AccountRepository,
    transactions: TransactionRepository,
    clock: Clock,
) : ViewModel() {
    private val accountId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_ID))
    private val month = Period.MONTH.rangeContaining(BogotaTime.today(clock))
    private val allTime = ClosedOpenRange(Instant.EPOCH, Instant.parse("2200-01-01T00:00:00Z"))

    val state: StateFlow<AccountDetailState> = combine(
        repository.observeAccount(accountId),
        repository.observeActivity(accountId, month),
        transactions.observe(allTime, accountId = accountId),
    ) { account, activity, history ->
        AccountDetailState(account, activity, history)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun reconcile(realBalance: Money) {
        viewModelScope.launch {
            runCatching { repository.reconcile(accountId, realBalance) }
                .onSuccess { diff ->
                    _message.value = if (diff.isZero) "El saldo ya coincidía. No se hizo ningún ajuste."
                    else "Se registró un ajuste de ${diff.formatCop()}. No cuenta como ingreso ni gasto."
                }
                .onFailure { _message.value = it.message }
        }
    }

    fun update(name: String, type: AccountType, initial: Money) {
        val current = state.value.account ?: return
        viewModelScope.launch {
            runCatching {
                require(name.isNotBlank()) { "Escribe un nombre" }
                repository.update(accountId, name, type, current.institution, initial)
            }.onFailure { _message.value = it.message }
        }
    }

    fun setArchived(archived: Boolean) {
        viewModelScope.launch { repository.setArchived(accountId, archived) }
    }

    suspend fun movementCount(): Int = repository.movementCount(accountId)

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.delete(accountId) }
                .onSuccess { onDone() }
                .onFailure { _message.value = it.message }
        }
    }

    fun clearMessage() { _message.value = null }

    companion object {
        const val ARG_ID = "accountId"
    }
}
