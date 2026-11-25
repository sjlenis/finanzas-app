package com.finanzaspersonales.feature.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.formatShortDate
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.nlp.DraftKind

/** Atajos: los de registro llenan el campo para completar el valor; las preguntas se envían de una. */
private data class QuickAction(val label: String, val text: String, val sendNow: Boolean)

private val quickActions = listOf(
    QuickAction("Almuerzo", "Almuerzo ", sendNow = false),
    QuickAction("Transporte", "Transporte ", sendNow = false),
    QuickAction("Café", "Café ", sendNow = false),
    QuickAction("¿Cuánto he gastado hoy?", "¿Cuánto he gastado hoy?", sendNow = true),
    QuickAction("¿Cuánto llevo este mes?", "¿Cuánto llevo este mes?", sendNow = true),
    QuickAction("¿Cuánto tengo?", "¿Cuánto dinero tengo?", sendNow = true),
    QuickAction("Ayuda", "ayuda", sendNow = true),
)

@Composable
fun AssistantRoute(onScanPhoto: () -> Unit = {}, viewModel: AssistantViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AssistantScreen(
        state = state,
        onSend = viewModel::send,
        onConfirm = { viewModel.confirm(it) },
        onCancel = { viewModel.cancel(it) },
        onEdit = viewModel::startEdit,
        onDefaultAccount = { viewModel.setDefaultAccount(it) },
        onClearHistory = { viewModel.clearHistory() },
        onScanPhoto = onScanPhoto,
    )
    state.editing?.let { item ->
        EditCardDialog(
            card = item.card!!,
            state = state,
            onDismiss = viewModel::dismissEdit,
            onApply = { card, makeDefault -> viewModel.applyEdit(item.id, card, makeDefault) },
        )
    }
}

@Composable
fun AssistantScreen(
    state: AssistantUiState,
    onSend: (String) -> Unit,
    onConfirm: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDefaultAccount: (Long) -> Unit,
    onClearHistory: () -> Unit,
    onScanPhoto: () -> Unit = {},
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val itemCount = state.messages.size + 1

    LaunchedEffect(itemCount, state.processing) {
        listState.animateScrollToItem(itemCount - 1)
    }

    fun submit() {
        if (input.text.isBlank() || state.processing) return
        onSend(input.text)
        input = TextFieldValue("")
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoiceField(
                label = "Cuenta predeterminada",
                selected = state.defaultAccount?.id,
                choices = state.accounts.map { Choice(it.id, it.name) },
                onSelect = onDefaultAccount,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onScanPhoto) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = "Registrar desde una foto")
            }
            IconButton(onClick = { confirmClear = true }, enabled = state.messages.isNotEmpty()) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = "Borrar historial del chat")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "welcome") { Bubble(text = FinancialAssistant.WELCOME, fromUser = false) }
            items(state.messages, key = { it.id }) { message ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bubble(text = message.text, fromUser = message.fromUser)
                    val card = message.card
                    val status = message.status
                    if (card != null && status != null) {
                        ConfirmationCard(
                            card = card,
                            status = status,
                            today = state.today,
                            enabled = !state.processing,
                            onConfirm = { onConfirm(message.id) },
                            onEdit = { onEdit(message.id) },
                            onCancel = { onCancel(message.id) },
                        )
                    }
                }
            }
        }

        if (state.processing) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Procesando…", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickActions.forEach { action ->
                AssistChip(
                    onClick = {
                        if (action.sendNow) {
                            if (!state.processing) onSend(action.text)
                        } else {
                            input = TextFieldValue(action.text, selection = TextRange(action.text.length))
                        }
                    },
                    label = { Text(action.label, maxLines = 1) },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ej.: Gasté 15.000 en almuerzo") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            IconButton(onClick = { submit() }, enabled = input.text.isNotBlank() && !state.processing) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar el historial del chat?") },
            text = { Text("Se borran los mensajes y las tarjetas sin confirmar. Los movimientos que ya guardaste no se tocan.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClearHistory() }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text = text,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (fromUser) 16.dp else 4.dp,
                        bottomEnd = if (fromUser) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConfirmationCard(
    card: DraftCard,
    status: CardStatus,
    today: java.time.LocalDate,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
) {
    val open = status == CardStatus.PENDING || status == CardStatus.AWAITING_AMOUNT
    ElevatedCard(Modifier.fillMaxWidth().padding(end = 24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Field("Tipo", card.kind.label)
            if (card.usesCategory) Field("Categoría", card.categoryName ?: "Sin categoría")
            Field("Descripción", card.description)
            Field(
                "Valor",
                card.amount?.formatCop() ?: "Falta el valor",
                highlight = card.amount == null,
            )
            Field("Fecha", if (card.date == today) "Hoy, ${formatShortDate(card.date)}" else formatShortDate(card.date))
            if (card.kind == DraftKind.WITHDRAWAL) {
                Field("Desde", accountLabel(card))
                Field("Hacia", card.toAccountName ?: "Sin cuenta de efectivo")
            } else {
                Field("Cuenta", accountLabel(card))
            }
            card.counterparty?.let { Field("Persona", it) }
            if (card.kind == DraftKind.LOAN_GIVEN || card.kind == DraftKind.LOAN_RECEIVED || card.kind == DraftKind.WITHDRAWAL) {
                Text(
                    "No cuenta como ingreso ni como gasto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(4.dp))
            when (status) {
                CardStatus.SAVED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                    Text("Guardado", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                CardStatus.CANCELLED -> Text(
                    "Cancelado: no se guardó",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status == CardStatus.PENDING) {
                        Button(onClick = onConfirm, enabled = enabled && open, modifier = Modifier.fillMaxWidth()) {
                            Text("Confirmar y guardar")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEdit, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Editar") }
                        TextButton(onClick = onCancel, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                    }
                }
            }
        }
    }
}

private fun accountLabel(card: DraftCard): String {
    val name = card.accountName ?: return "Sin cuenta"
    return if (card.accountIsDefault) "$name (predeterminada)" else name
}

@Composable
private fun Field(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
