package com.finanzaspersonales.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.TransactionRepository
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.EmptyState
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import com.finanzaspersonales.core.designsystem.component.formatShortDate
import com.finanzaspersonales.core.designsystem.component.label
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.Transaction
import com.finanzaspersonales.core.model.TransactionKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class PendingUiState(
    val loading: Boolean = true,
    val pending: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PendingViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    accounts: AccountRepository,
) : ViewModel() {
    private val errorMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<PendingUiState> = combine(
        transactions.observePending(),
        accounts.observeAccounts(),
        errorMessage,
    ) { pending, accountList, err -> PendingUiState(false, pending, accountList, err) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PendingUiState())

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        errorMessage.value = null
        runCatching { block() }.onFailure { errorMessage.value = it.message ?: "No se pudo completar" }
    }

    fun confirm(tx: Transaction) = run {
        val cash = if (tx.kind == TransactionKind.WITHDRAWAL) {
            state.value.accounts.firstOrNull { it.type == AccountType.CASH }?.id
                ?: throw IllegalStateException("Para un retiro necesitas una cuenta de efectivo activa")
        } else {
            null
        }
        transactions.confirmPending(tx.id, cash)
    }

    fun confirmAll() = run { state.value.pending.forEach { tx -> if (tx.kind != TransactionKind.WITHDRAWAL) transactions.confirmPending(tx.id) } }

    fun toTransfer(tx: Transaction, otherAccountId: Long) = run { transactions.pendingToTransfer(tx.id, otherAccountId) }

    fun discard(tx: Transaction) = run { transactions.discardPending(tx.id) }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingRoute(onBack: () -> Unit, onEdit: (Long) -> Unit, viewModel: PendingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var transferFor by remember { mutableStateOf<Transaction?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Por revisar") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    if (state.pending.size > 1) {
                        IconButton(onClick = { confirmAll = true }) { Icon(Icons.Outlined.DoneAll, contentDescription = "Confirmar todos") }
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loading && state.pending.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Outlined.DoneAll,
                    title = "Nada por revisar",
                    message = "Aquí aparecen los movimientos detectados en notificaciones de Nequi y Bancolombia. " +
                        "Ninguno afecta tus saldos hasta que lo confirmes.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { msg ->
                item { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.pending, key = { it.id }) { tx ->
                PendingCard(
                    tx = tx,
                    onConfirm = { viewModel.confirm(tx) },
                    onEdit = { onEdit(tx.id) },
                    onTransfer = { transferFor = tx },
                    onDiscard = { viewModel.discard(tx) },
                )
            }
        }
    }

    transferFor?.let { tx ->
        var other by remember(tx.id) { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { transferFor = null },
            title = { Text("Fue entre mis cuentas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (tx.amount.isNegative) "El dinero salió de ${tx.accountName}. ¿A cuál de tus cuentas llegó?"
                        else "El dinero llegó a ${tx.accountName}. ¿De cuál de tus cuentas salió?",
                    )
                    ChoiceField(
                        label = "Cuenta",
                        selected = other,
                        choices = state.accounts.filter { it.id != tx.accountId }.map { Choice(it.id, it.name) },
                        onSelect = { other = it },
                    )
                    Text(
                        "Se guardará como transferencia: no cuenta como gasto ni como ingreso.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = other != null, onClick = {
                    other?.let { viewModel.toTransfer(tx, it) }
                    transferFor = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { transferFor = null }) { Text("Cancelar") } },
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("¿Confirmar todos?") },
            text = { Text("Se confirman tal como están. Los retiros se revisan uno por uno.") },
            confirmButton = { TextButton(onClick = { confirmAll = false; viewModel.confirmAll() }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(
    tx: Transaction,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onTransfer: () -> Unit,
    onDiscard: () -> Unit,
) {
    val date = BogotaTime.dateOf(tx.occurredAt)
    val time = tx.occurredAt.atZone(BogotaTime.ZONE).format(timeFormat)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tx.description, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                MoneyText(tx.amount, tone = if (tx.kind == TransactionKind.WITHDRAWAL) MoneyTone.Neutral else MoneyTone.Signed)
            }
            Text(
                "${tx.kind.label()} · ${tx.accountName} · ${formatShortDate(date)}, $time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tx.notes?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text(if (tx.kind == TransactionKind.WITHDRAWAL) "Confirmar retiro" else "Confirmar") }
                if (tx.kind != TransactionKind.WITHDRAWAL) OutlinedButton(onClick = onEdit) { Text("Editar") }
                if (tx.kind == TransactionKind.EXPENSE || tx.kind == TransactionKind.INCOME) {
                    OutlinedButton(onClick = onTransfer) { Text("Entre mis cuentas") }
                }
                TextButton(onClick = onDiscard) { Text("Descartar") }
            }
        }
    }
}
