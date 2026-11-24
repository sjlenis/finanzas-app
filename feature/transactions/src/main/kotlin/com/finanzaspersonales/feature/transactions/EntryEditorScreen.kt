package com.finanzaspersonales.feature.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.DateField
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.ManualEntryType

private val modes: List<Pair<EntryMode, String>> = listOf(
    EntryMode.Manual(ManualEntryType.EXPENSE) to "Gasto",
    EntryMode.Manual(ManualEntryType.INCOME) to "Ingreso",
    EntryMode.Transfer to "Transferencia",
    EntryMode.Manual(ManualEntryType.REFUND) to "Devolución",
    EntryMode.Manual(ManualEntryType.FEE) to "Comisión",
)

@Composable
fun EntryEditorRoute(onDone: () -> Unit, viewModel: EntryEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    EntryEditorScreen(
        state = state,
        onBack = onDone,
        onMode = viewModel::setMode,
        onDigits = viewModel::setDigits,
        onAccount = viewModel::setAccount,
        onToAccount = viewModel::setToAccount,
        onCategory = viewModel::setCategory,
        onDescription = viewModel::setDescription,
        onDate = viewModel::setDate,
        onNotes = viewModel::setNotes,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onDismissError = viewModel::dismissError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorScreen(
    state: EntryEditorState,
    onBack: () -> Unit,
    onMode: (EntryMode) -> Unit,
    onDigits: (String) -> Unit,
    onAccount: (Long) -> Unit,
    onToAccount: (Long) -> Unit,
    onCategory: (Long?) -> Unit,
    onDescription: (String) -> Unit,
    onDate: (java.time.LocalDate) -> Unit,
    onNotes: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Registrar movimiento" else "Editar movimiento") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Eliminar") }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.readOnlyReason != null) {
                Text(state.readOnlyReason, style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (mode, label) ->
                    FilterChip(selected = state.mode == mode, onClick = { onMode(mode) }, label = { Text(label) })
                }
            }
            val showErrors = state.showErrors
            AmountField(
                digits = state.digits,
                onDigitsChange = onDigits,
                isError = showErrors && state.amountError != null,
                supportingText = if (showErrors) state.amountError else null,
            )
            val accountChoices = state.accounts.map { Choice(it.id, it.name) }
            ChoiceField(
                label = if (state.mode == EntryMode.Transfer) "Desde la cuenta" else "Cuenta o medio de pago",
                selected = state.accountId,
                choices = accountChoices,
                onSelect = onAccount,
                isError = showErrors && state.accountError != null,
            )
            if (state.mode == EntryMode.Transfer) {
                ChoiceField(
                    label = "Hacia la cuenta",
                    selected = state.toAccountId,
                    choices = accountChoices,
                    onSelect = onToAccount,
                    isError = showErrors && state.toAccountError != null,
                )
                if (showErrors && state.toAccountError != null) {
                    Text(state.toAccountError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Las transferencias entre tus cuentas (incluido un retiro de cajero hacia Efectivo) no cuentan como ingreso ni gasto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ChoiceField(
                    label = "Categoría",
                    selected = state.categoryId,
                    choices = listOf(Choice<Long?>(null, "Sin categoría")) + state.categoryChoices.toChoices(),
                    onSelect = onCategory,
                )
            }
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescription,
                label = { Text("Descripción") },
                placeholder = { Text("Ej.: Almuerzo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(date = state.date, onDateChange = onDate, maxDate = state.today)
            if (state.mode != EntryMode.Transfer) {
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = onNotes,
                    label = { Text("Observaciones (opcional)") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.saving) "Guardando…" else "Guardar")
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar movimiento?") },
            text = {
                Text(
                    if (state.mode == EntryMode.Transfer) "Se eliminarán los dos lados de la transferencia y los saldos se recalcularán."
                    else "El saldo de la cuenta se recalculará. Esta acción queda en el registro de cambios.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("No se pudo completar") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("Entendido") } },
        )
    }
}

/** Subcategorías debajo de su categoría, con sangría. */
private fun List<Category>.toChoices(): List<Choice<Long?>> {
    val parents = filter { it.parentId == null }
    return parents.flatMap { parent ->
        listOf(Choice<Long?>(parent.id, parent.name)) +
            filter { it.parentId == parent.id }.map { Choice<Long?>(it.id, "   ${it.name}") }
    }
}
