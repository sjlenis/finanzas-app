package com.finanzaspersonales.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.instantForDate
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.data.EditableEntry
import com.finanzaspersonales.core.data.TransactionRepository
import com.finanzaspersonales.core.designsystem.component.digitsToMoney
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.CategoryClass
import com.finanzaspersonales.core.model.ManualEntryType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** Qué se está registrando: un movimiento de una cuenta o una transferencia entre cuentas. */
sealed interface EntryMode {
    data class Manual(val type: ManualEntryType) : EntryMode
    data object Transfer : EntryMode
}

data class EntryEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val readOnlyReason: String? = null,
    val mode: EntryMode = EntryMode.Manual(ManualEntryType.EXPENSE),
    val digits: String = "",
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val description: String = "",
    val date: LocalDate,
    val today: LocalDate,
    val notes: String = "",
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
) {
    val amountError: String? get() = if (digitsToMoney(digits) == null) "Escribe un valor mayor que cero" else null
    val accountError: String? get() = if (accountId == null) "Elige una cuenta" else null
    val toAccountError: String?
        get() = when {
            mode != EntryMode.Transfer -> null
            toAccountId == null -> "Elige la cuenta de destino"
            toAccountId == accountId -> "La cuenta de destino debe ser distinta"
            else -> null
        }
    val isValid: Boolean get() = amountError == null && accountError == null && toAccountError == null

    /** Categorías que aplican al tipo elegido: de gasto para gastos y comisiones, de ingreso para ingresos. */
    val categoryChoices: List<Category>
        get() {
            val wanted = when ((mode as? EntryMode.Manual)?.type) {
                ManualEntryType.INCOME -> CategoryClass.INCOME
                ManualEntryType.EXPENSE, ManualEntryType.FEE, ManualEntryType.REFUND -> CategoryClass.EXPENSE
                null -> return emptyList()
            }
            return categories.filter { it.categoryClass == wanted }
        }
}

@HiltViewModel
class EntryEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactions: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    private val transactionId: Long? = savedStateHandle.get<Long>(ARG_ID)?.takeIf { it > 0 }
    private var transferId: Long? = null
    private val today = BogotaTime.today(clock)

    private val _state = MutableStateFlow(EntryEditorState(date = today, today = today))
    val state: StateFlow<EntryEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val accounts = accountRepository.observeAccounts().first()
            val categories = categoryRepository.observeAll().first()
            var s = _state.value.copy(
                loading = false,
                accounts = accounts,
                categories = categories,
                accountId = accounts.firstOrNull()?.id,
            )
            if (transactionId != null) {
                s = when (val e = transactions.getEditable(transactionId)) {
                    is EditableEntry.Manual -> s.copy(
                        isNew = false,
                        mode = EntryMode.Manual(e.type),
                        digits = e.amount.pesos.toString(),
                        accountId = e.accountId,
                        categoryId = e.categoryId,
                        description = e.description,
                        date = BogotaTime.dateOf(e.occurredAt),
                        notes = e.notes.orEmpty(),
                    )
                    is EditableEntry.Transfer -> {
                        transferId = e.transferId
                        s.copy(
                            isNew = false,
                            mode = EntryMode.Transfer,
                            digits = e.amount.pesos.toString(),
                            accountId = e.fromAccountId,
                            toAccountId = e.toAccountId,
                            description = e.description,
                            date = BogotaTime.dateOf(e.occurredAt),
                        )
                    }
                    is EditableEntry.ReadOnly -> s.copy(isNew = false, readOnlyReason = e.reason)
                    null -> s.copy(error = "El movimiento ya no existe", done = true)
                }
            }
            _state.value = s
        }
    }

    fun setMode(mode: EntryMode) = _state.update {
        // Al cambiar de tipo, una categoría que no corresponde se limpia.
        val next = it.copy(mode = mode)
        next.copy(categoryId = it.categoryId?.takeIf { id -> next.categoryChoices.any { c -> c.id == id } })
    }
    fun setDigits(v: String) = _state.update { it.copy(digits = v) }
    fun setAccount(id: Long) = _state.update { it.copy(accountId = id) }
    fun setToAccount(id: Long) = _state.update { it.copy(toAccountId = id) }
    fun setCategory(id: Long?) = _state.update { it.copy(categoryId = id) }
    fun setDescription(v: String) = _state.update { it.copy(description = v.take(80)) }
    fun setDate(d: LocalDate) = _state.update { it.copy(date = d) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v.take(300)) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun save() {
        val s = _state.value
        if (!s.isValid) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        val amount = digitsToMoney(s.digits)!!
        val at = BogotaTime.instantForDate(s.date, clock)
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                when (val mode = s.mode) {
                    is EntryMode.Manual -> {
                        // Si el movimiento era una transferencia y ahora es otro tipo, se reemplaza.
                        transferId?.let { _ -> transactionId?.let { transactions.delete(it) } }
                        transactions.saveManual(
                            id = transactionId.takeIf { transferId == null },
                            type = mode.type,
                            amount = amount,
                            accountId = s.accountId!!,
                            categoryId = s.categoryId,
                            description = s.description,
                            occurredAt = at,
                            notes = s.notes,
                        )
                    }
                    EntryMode.Transfer -> {
                        // Un movimiento normal convertido en transferencia: se borra y se crea la transferencia.
                        if (transferId == null) transactionId?.let { transactions.delete(it) }
                        transactions.saveTransfer(transferId, s.accountId!!, s.toAccountId!!, amount, s.description, at)
                    }
                }
            }.onSuccess {
                _state.update { it.copy(saving = false, done = true) }
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = e.message ?: "No se pudo guardar") }
            }
        }
    }

    fun delete() {
        val id = transactionId ?: return
        viewModelScope.launch {
            runCatching { transactions.delete(id) }
                .onSuccess { _state.update { it.copy(done = true) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "No se pudo eliminar") } }
        }
    }

    companion object {
        const val ARG_ID = "id"
    }
}
