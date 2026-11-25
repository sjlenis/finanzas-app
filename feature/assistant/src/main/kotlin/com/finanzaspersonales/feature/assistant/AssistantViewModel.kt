package com.finanzaspersonales.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.data.ChatRepository
import com.finanzaspersonales.core.data.ChatRole
import com.finanzaspersonales.core.data.UserPreferences
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject

data class ChatItem(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val card: DraftCard? = null,
    val status: CardStatus? = null,
)

data class AssistantUiState(
    val loading: Boolean = true,
    val messages: List<ChatItem> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val defaultAccount: Account? = null,
    val processing: Boolean = false,
    /** Mensaje cuya tarjeta se está editando. */
    val editingId: Long? = null,
    val today: LocalDate = LocalDate.now(),
) {
    val editing: ChatItem? get() = messages.firstOrNull { it.id == editingId && it.card != null }
}

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val assistant: FinancialAssistant,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val preferences: UserPreferences,
) : ViewModel() {

    private val processing = MutableStateFlow(false)
    private val editingId = MutableStateFlow<Long?>(null)
    /** Un mensaje a la vez, para que las respuestas no se crucen. */
    private val mutex = Mutex()

    private val data = combine(
        accounts.observeAccounts(),
        categories.observeAll(),
        preferences.defaultAccountId,
    ) { a, c, d -> AssistantData(a, c, d) }

    val state: StateFlow<AssistantUiState> = combine(chat.observe(), data, processing, editingId) { stored, d, busy, editing ->
        AssistantUiState(
            loading = false,
            messages = stored.map { m ->
                ChatItem(
                    id = m.id,
                    fromUser = m.role == ChatRole.USER,
                    text = m.text,
                    card = m.cardJson?.let(DraftCard::fromJson),
                    status = CardStatus.from(m.cardStatus),
                )
            },
            accounts = d.accounts,
            categories = d.categories,
            defaultAccount = d.defaultAccount,
            processing = busy,
            editingId = editing,
            today = assistant.today(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantUiState())

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            mutex.withLock {
                processing.value = true
                try {
                    chat.add(ChatRole.USER, text)
                    val current = data.first()
                    if (!fillPendingAmount(text)) {
                        val reply = runCatching { assistant.reply(text, current) }
                            .getOrElse { AssistantReply("No pude procesar el mensaje: ${it.message ?: "error desconocido"}") }
                        chat.add(ChatRole.ASSISTANT, reply.text, reply.card?.toJson(), reply.status?.name)
                    }
                } finally {
                    processing.value = false
                }
            }
        }
    }

    /** Si el asistente preguntó "¿Cuánto fue?" y la respuesta es solo un valor, completa esa tarjeta. */
    private suspend fun fillPendingAmount(text: String): Boolean {
        val amount = assistant.amountReply(text) ?: return false
        val waiting = chat.observe().first().lastOrNull { CardStatus.from(it.cardStatus) == CardStatus.AWAITING_AMOUNT } ?: return false
        val card = waiting.cardJson?.let(DraftCard::fromJson)?.copy(amount = amount) ?: return false
        chat.updateCard(waiting.id, card.toJson(), CardStatus.PENDING.name)
        chat.add(ChatRole.ASSISTANT, "Listo, completé el valor. Revisa la tarjeta y confírmala.")
        return true
    }

    fun confirm(messageId: Long) = viewModelScope.launch {
        mutex.withLock {
            val stored = chat.get(messageId) ?: return@withLock
            if (CardStatus.from(stored.cardStatus) != CardStatus.PENDING) return@withLock
            val card = stored.cardJson?.let(DraftCard::fromJson) ?: return@withLock
            processing.value = true
            try {
                val id = assistant.save(card, data.first())
                chat.updateCard(messageId, stored.cardJson, CardStatus.SAVED.name, transactionId = id)
                val extra = runCatching { assistant.applyObligation(card, id) }
                    .getOrElse { "El movimiento se guardó, pero no pude abonarlo al pago fijo: ${it.message}" }
                chat.add(ChatRole.ASSISTANT, assistant.savedText(card) + (extra?.let { " $it" } ?: ""))
            } catch (e: Exception) {
                chat.add(ChatRole.ASSISTANT, "No se guardó: ${e.message ?: "error desconocido"}. Puedes editar la tarjeta y volver a intentarlo.")
            } finally {
                processing.value = false
            }
        }
    }

    fun cancel(messageId: Long) = viewModelScope.launch {
        mutex.withLock {
            val stored = chat.get(messageId) ?: return@withLock
            val status = CardStatus.from(stored.cardStatus)
            if (status != CardStatus.PENDING && status != CardStatus.AWAITING_AMOUNT) return@withLock
            chat.updateCard(messageId, stored.cardJson, CardStatus.CANCELLED.name)
            chat.add(ChatRole.ASSISTANT, "Cancelado. No se guardó nada.")
        }
    }

    /** "No abonar": el movimiento se guarda suelto, sin tocar el pago fijo. */
    fun detachObligation(messageId: Long) = viewModelScope.launch {
        mutex.withLock {
            val stored = chat.get(messageId) ?: return@withLock
            val status = CardStatus.from(stored.cardStatus)
            if (status != CardStatus.PENDING && status != CardStatus.AWAITING_AMOUNT) return@withLock
            val card = stored.cardJson?.let(DraftCard::fromJson) ?: return@withLock
            chat.updateCard(messageId, card.copy(obligationId = null, obligationName = null, obligationRemaining = null).toJson(), stored.cardStatus)
        }
    }

    fun startEdit(messageId: Long) { editingId.value = messageId }

    fun dismissEdit() { editingId.value = null }

    fun applyEdit(messageId: Long, card: DraftCard, makeDefault: Boolean) = viewModelScope.launch {
        mutex.withLock {
            val stored = chat.get(messageId) ?: return@withLock
            val status = CardStatus.from(stored.cardStatus)
            if (status != CardStatus.PENDING && status != CardStatus.AWAITING_AMOUNT) return@withLock
            val newStatus = if (card.amount == null) CardStatus.AWAITING_AMOUNT else CardStatus.PENDING
            val before = stored.cardJson?.let(DraftCard::fromJson)
            val kept = if (before != null && before.kind == card.kind && card.obligationId == null) {
                card.copy(obligationId = before.obligationId, obligationName = before.obligationName, obligationRemaining = before.obligationRemaining)
            } else card
            chat.updateCard(messageId, kept.toJson(), newStatus.name)
            if (makeDefault) card.accountId?.let { preferences.setDefaultAccountId(it) }
            editingId.value = null
        }
    }

    fun setDefaultAccount(id: Long) = viewModelScope.launch { preferences.setDefaultAccountId(id) }

    fun clearHistory() = viewModelScope.launch { mutex.withLock { chat.clear() } }
}
