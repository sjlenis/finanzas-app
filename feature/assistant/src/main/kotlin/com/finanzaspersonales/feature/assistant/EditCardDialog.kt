package com.finanzaspersonales.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.DateField
import com.finanzaspersonales.core.designsystem.component.digitsToMoney
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.engine.nlp.DraftKind

private const val NO_CATEGORY = -1L

/** Edición de la tarjeta antes de guardar. No guarda nada: solo cambia el borrador. */
@Composable
internal fun EditCardDialog(
    card: DraftCard,
    state: AssistantUiState,
    onDismiss: () -> Unit,
    onApply: (DraftCard, makeDefault: Boolean) -> Unit,
) {
    var kind by remember { mutableStateOf(card.kind) }
    // Si el valor tiene centavos se conservan mientras no se cambie el campo.
    val originalDigits = card.amount?.takeIf { it.centavos > 0 }?.pesos?.toString().orEmpty()
    var digits by remember { mutableStateOf(originalDigits) }
    var categoryId by remember { mutableStateOf(card.categoryId) }
    var description by remember { mutableStateOf(card.description) }
    var date by remember { mutableStateOf(card.date) }
    var accountId by remember { mutableStateOf(card.accountId) }
    var toAccountId by remember { mutableStateOf(card.toAccountId) }
    var makeDefault by remember { mutableStateOf(false) }

    val wantedClass = FinancialAssistant.classFor(kind)
    val categoryChoices = listOf(Choice(NO_CATEGORY, "Sin categoría")) +
        state.categories.filter { it.categoryClass == wantedClass }.map { Choice(it.id, if (it.parentId != null) "  ${it.name}" else it.name) }
    val sourceAccounts = if (kind == DraftKind.WITHDRAWAL) state.accounts.filter { it.type != AccountType.CASH } else state.accounts
    val cashAccounts = state.accounts.filter { it.type == AccountType.CASH }
    val amount = if (digits == originalDigits) card.amount else digitsToMoney(digits)
    val withdrawalOk = kind != DraftKind.WITHDRAWAL || (toAccountId != null && toAccountId != accountId)
    val valid = accountId != null && withdrawalOk && description.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar antes de guardar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceField(
                    label = "Tipo",
                    selected = kind,
                    choices = DraftKind.entries.map { Choice(it, it.label) },
                    onSelect = { new ->
                        if (FinancialAssistant.classFor(new) != FinancialAssistant.classFor(kind)) {
                            categoryId = if (new == DraftKind.LOAN_GIVEN || new == DraftKind.LOAN_RECEIVED) {
                                state.categories.firstOrNull { it.name == "Préstamos" }?.id
                            } else {
                                null
                            }
                        }
                        if (new == DraftKind.WITHDRAWAL) {
                            toAccountId = toAccountId ?: cashAccounts.firstOrNull()?.id
                            if (state.accounts.firstOrNull { it.id == accountId }?.type == AccountType.CASH) {
                                accountId = state.accounts.firstOrNull { it.type != AccountType.CASH }?.id
                            }
                            if (description == card.description && kind != DraftKind.WITHDRAWAL) description = "Retiro a efectivo"
                        }
                        kind = new
                    },
                )
                AmountField(
                    digits = digits,
                    onDigitsChange = { digits = it },
                    supportingText = if (amount == null) "Si lo dejas vacío, el asistente te lo vuelve a preguntar" else null,
                )
                if (kind != DraftKind.WITHDRAWAL) {
                    ChoiceField(
                        label = "Categoría",
                        selected = categoryId ?: NO_CATEGORY,
                        choices = categoryChoices,
                        onSelect = { categoryId = it.takeIf { id -> id != NO_CATEGORY } },
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(80) },
                    label = { Text("Descripción") },
                    singleLine = true,
                    isError = description.isBlank(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                DateField(date = date, onDateChange = { date = it }, maxDate = state.today)
                ChoiceField(
                    label = if (kind == DraftKind.WITHDRAWAL) "Desde" else "Cuenta",
                    selected = accountId,
                    choices = sourceAccounts.map { Choice(it.id, it.name) },
                    onSelect = { accountId = it },
                    isError = accountId == null,
                )
                if (kind == DraftKind.WITHDRAWAL) {
                    ChoiceField(
                        label = "Hacia (efectivo)",
                        selected = toAccountId,
                        choices = cashAccounts.map { Choice(it.id, it.name) },
                        onSelect = { toAccountId = it },
                        isError = !withdrawalOk,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = makeDefault, onCheckedChange = { makeDefault = it })
                    Text("Usar esta cuenta como predeterminada")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val account = state.accounts.firstOrNull { it.id == accountId }
                    val category = state.categories.firstOrNull { it.id == categoryId }
                    val cash = state.accounts.firstOrNull { it.id == toAccountId }
                    onApply(
                        card.copy(
                            kind = kind,
                            amount = amount,
                            categoryId = if (kind == DraftKind.WITHDRAWAL) null else category?.id,
                            categoryName = if (kind == DraftKind.WITHDRAWAL) null else category?.name,
                            description = description.trim(),
                            date = date,
                            accountId = account?.id,
                            accountName = account?.name,
                            accountIsDefault = card.accountIsDefault && account?.id == card.accountId,
                            toAccountId = if (kind == DraftKind.WITHDRAWAL) cash?.id else null,
                            toAccountName = if (kind == DraftKind.WITHDRAWAL) cash?.name else null,
                            counterparty = if (kind == DraftKind.LOAN_GIVEN || kind == DraftKind.LOAN_RECEIVED) card.counterparty else null,
                        ),
                        makeDefault,
                    )
                },
            ) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
