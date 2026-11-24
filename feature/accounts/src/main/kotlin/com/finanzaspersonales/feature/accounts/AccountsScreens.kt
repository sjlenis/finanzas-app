package com.finanzaspersonales.feature.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import com.finanzaspersonales.core.designsystem.component.TransactionItem
import com.finanzaspersonales.core.designsystem.theme.AmountLarge
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsRoute(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cuentas") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Nueva cuenta") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(accounts.orEmpty(), key = { it.id }) { account ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(account.id) },
                    headlineContent = { Text(account.name) },
                    supportingContent = { Text(if (account.isArchived) "${account.type.label()} · Archivada" else account.type.label()) },
                    trailingContent = { MoneyText(account.balance) },
                )
            }
        }
    }
    if (adding) {
        AccountDialog(
            title = "Nueva cuenta",
            initialName = "",
            initialType = AccountType.BANK,
            initialBalance = Money.ZERO,
            onDismiss = { adding = false },
            onConfirm = { name, type, initial -> viewModel.create(name, type, initial) { adding = false } },
        )
    }
    message?.let { InfoDialog(it, viewModel::clearMessage) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailRoute(
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var reconciling by remember { mutableStateOf(false) }
    val account = state.account

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.name ?: "Cuenta") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = { IconButton(onClick = { editing = true }, enabled = account != null) { Icon(Icons.Outlined.Edit, "Editar") } },
            )
        },
    ) { padding ->
        if (account == null) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FinanceCard(title = "Saldo actual calculado") {
                    MoneyText(account.balance, style = AmountLarge)
                    Text("Saldo inicial: ", style = MaterialTheme.typography.bodySmall)
                    MoneyText(account.initialBalance)
                    OutlinedButton(onClick = { reconciling = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Conciliar con el saldo real")
                    }
                }
            }
            state.monthActivity?.let { a ->
                item {
                    FinanceCard(title = "Este mes") {
                        ActivityRow("Ingresos", a.income, MoneyTone.Income)
                        ActivityRow("Gastos", a.expense, MoneyTone.Expense)
                        ActivityRow("Transferencias recibidas", a.transfersIn, MoneyTone.Neutral)
                        ActivityRow("Transferencias enviadas", a.transfersOut, MoneyTone.Neutral)
                    }
                }
            }
            item { Text("Historial", style = MaterialTheme.typography.titleMedium) }
            if (state.history.isEmpty()) {
                item { Text("Sin movimientos todavía.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.history, key = { it.id }) { tx ->
                TransactionItem(tx, onClick = { onOpenTransaction(tx.id) })
                HorizontalDivider()
            }
            item {
                TextButton(onClick = { viewModel.setArchived(!account.isArchived) }) {
                    Text(if (account.isArchived) "Reactivar cuenta" else "Archivar cuenta (se oculta del inicio, conserva su historial)")
                }
            }
        }
    }

    if (editing && account != null) {
        AccountDialog(
            title = "Editar cuenta",
            initialName = account.name,
            initialType = account.type,
            initialBalance = account.initialBalance,
            onDismiss = { editing = false },
            onConfirm = { name, type, initial -> viewModel.update(name, type, initial); editing = false },
            note = "Cambiar el saldo inicial recalcula el saldo actual. Si solo quieres cuadrar con tu banco, usa \"Conciliar\".",
        )
    }
    if (reconciling && account != null) {
        var digits by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reconciling = false },
            title = { Text("Conciliar ${account.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Escribe el saldo que ves hoy en tu banco o billetera. Si no coincide, se crea un ajuste por la diferencia.")
                    AmountField(digits = digits, onDigitsChange = { digits = it }, label = "Saldo real")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.reconcile(Money.ofPesos(digits.toLongOrNull() ?: 0))
                    reconciling = false
                }) { Text("Conciliar") }
            },
            dismissButton = { TextButton(onClick = { reconciling = false }) { Text("Cancelar") } },
        )
    }
    message?.let { InfoDialog(it, viewModel::clearMessage) }
}

@Composable
private fun ActivityRow(label: String, amount: Money, tone: MoneyTone) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        MoneyText(amount, tone = tone)
    }
}

@Composable
private fun AccountDialog(
    title: String,
    initialName: String,
    initialType: AccountType,
    initialBalance: Money,
    onDismiss: () -> Unit,
    onConfirm: (String, AccountType, Money) -> Unit,
    note: String? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var digits by remember { mutableStateOf(initialBalance.pesos.takeIf { it > 0 }?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nombre") }, singleLine = true)
                ChoiceField(
                    label = "Tipo",
                    selected = type,
                    choices = AccountType.entries.map { Choice(it, it.label()) },
                    onSelect = { type = it },
                )
                AmountField(digits = digits, onDigitsChange = { digits = it }, label = "Saldo inicial")
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, type, Money.ofPesos(digits.toLongOrNull() ?: 0)) },
                enabled = name.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun InfoDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Entendido") } },
    )
}
