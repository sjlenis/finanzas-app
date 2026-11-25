package com.finanzaspersonales.feature.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.data.Obligation
import com.finanzaspersonales.core.data.UpcomingItem
import com.finanzaspersonales.core.data.UpcomingType
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.formatShortDate
import com.finanzaspersonales.core.model.CategoryClass
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import java.time.YearMonth

/**
 * Calendario financiero del mes: pagos fijos, cuotas de deudas e ingresos previstos.
 * Las cuotas de deudas salen solas de la sección Deudas; aquí se agregan los demás.
 */
@Composable
fun CalendarRoute(onBack: () -> Unit, onOpenDebt: (Long) -> Unit, viewModel: CalendarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf<ObligationForm?>(null) }
    var selected by remember { mutableStateOf<UpcomingItem?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    val overview = state.overview

    PlanningScaffold("Calendario de pagos", onBack, fabLabel = "Agregar", onFab = { form = ObligationForm() }) {
        if (overview == null) return@PlanningScaffold
        val month = YearMonth.from(overview.today)
        item {
            FinanceCard(title = month.label().replaceFirstChar { it.uppercase() }) {
                LabeledMoney("Pagos pendientes", overview.money.obligations)
                LabeledMoney("Ingresos por recibir", overview.expectedIncome)
                overview.nextIncome?.let { LabeledText("Próximo ingreso", formatShortDate(it)) }
                Note("Las cuotas de tus deudas aparecen solas. Agrega aquí arriendo, servicios, suscripciones o tu salario.")
            }
        }
        if (overview.monthItems.isEmpty()) {
            item { Note("Aún no tienes pagos ni ingresos fijos. Toca «Agregar» para crear el primero.") }
        }
        items(overview.monthItems, key = { "${it.type}-${it.id}" }) { item ->
            CalendarRow(item, isLate = !item.paid && item.date.isBefore(overview.today)) {
                if (item.type == UpcomingType.DEBT) onOpenDebt(item.id) else selected = item
            }
        }
        val paused = state.obligations.filter { !it.active }
        if (paused.isNotEmpty()) {
            item { Text("Pausados", style = MaterialTheme.typography.titleMedium) }
            items(paused, key = { "paused-${it.id}" }) { o ->
                Row(
                    Modifier.fillMaxWidth().clickable { form = ObligationForm.from(o) }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(o.name, style = MaterialTheme.typography.bodyMedium)
                    MoneyText(o.amount)
                }
            }
        }
    }

    selected?.let { item ->
        val obligation = state.obligations.firstOrNull { it.id == item.id } ?: return@let
        ItemDialog(
            item = item,
            obligation = obligation,
            accounts = state.accounts.map { Choice(it.id, it.name) },
            onDismiss = { selected = null },
            onMarkPaid = { accountId -> viewModel.markPaid(item.id, accountId) { selected = null } },
            onUnmark = { viewModel.unmarkPaid(item.id) { selected = null } },
            onEdit = { selected = null; form = ObligationForm.from(obligation) },
        )
    }
    form?.let { f ->
        ObligationDialog(
            initial = f,
            accounts = state.accounts.map { Choice(it.id, it.name) },
            expenseCategories = state.categories.filter { it.categoryClass == CategoryClass.EXPENSE }.map { Choice(it.id, it.name) },
            incomeCategories = state.categories.filter { it.categoryClass == CategoryClass.INCOME }.map { Choice(it.id, it.name) },
            onDismiss = { form = null },
            onSave = { viewModel.save(it) { form = null } },
            onDelete = f.id?.let { id -> { form = null; deleting = id } },
        )
    }
    deleting?.let { id ->
        ConfirmDialog(
            "¿Eliminar este pago fijo?", "Deja de aparecer en el calendario. Los movimientos ya registrados se conservan.", "Eliminar",
            { viewModel.delete(id) {} },
        ) { deleting = null }
    }
    message?.let { MessageDialog(it, viewModel::clearMessage) }
}

private fun UpcomingType.label() = when (this) {
    UpcomingType.OBLIGATION -> "Pago fijo"
    UpcomingType.DEBT -> "Cuota de deuda"
    UpcomingType.INCOME -> "Ingreso"
}

@Composable
private fun CalendarRow(item: UpcomingItem, isLate: Boolean, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                val status = when {
                    item.paid && item.type == UpcomingType.INCOME -> "Recibido"
                    item.paid -> "Pagado"
                    isLate && item.type == UpcomingType.INCOME -> "Aún no recibido"
                    isLate -> "Vencido"
                    else -> "Pendiente"
                }
                Note("${formatShortDate(item.date)} · ${item.type.label()} · $status", error = isLate)
            }
            MoneyText(item.amount, tone = if (item.type == UpcomingType.INCOME) MoneyTone.Income else MoneyTone.Expense)
        }
    }
}

@Composable
private fun ItemDialog(
    item: UpcomingItem,
    obligation: Obligation,
    accounts: List<Choice<Long>>,
    onDismiss: () -> Unit,
    onMarkPaid: (Long?) -> Unit,
    onUnmark: () -> Unit,
    onEdit: () -> Unit,
) {
    val income = item.type == UpcomingType.INCOME
    var accountId by remember { mutableStateOf(obligation.accountId ?: accounts.firstOrNull()?.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            DialogColumn {
                LabeledMoney("Valor", item.amount)
                LabeledText("Fecha", formatShortDate(item.date))
                if (item.paid) {
                    Note(
                        (if (income) "Ya lo marcaste como recibido este mes." else "Ya lo marcaste como pagado este mes.") +
                            " Si lo desmarcas, el movimiento que se haya registrado se conserva: bórralo en Movimientos si no corresponde.",
                    )
                } else {
                    ChoiceField(
                        label = if (income) "Registrar el ingreso en" else "Registrar el gasto en",
                        selected = accountId ?: -1L,
                        choices = listOf(Choice(-1L, "No registrar movimiento (ya lo anoté)")) + accounts,
                        onSelect = { accountId = it.takeIf { id -> id != -1L } },
                    )
                    Note("Si eliges una cuenta, se crea el movimiento con la fecha de hoy y se actualiza el saldo.")
                }
            }
        },
        confirmButton = {
            if (item.paid) TextButton(onClick = onUnmark) { Text("Desmarcar") }
            else TextButton(onClick = { onMarkPaid(accountId) }) { Text(if (income) "Marcar recibido" else "Marcar pagado") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEdit) { Text("Editar") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

@Composable
private fun ObligationDialog(
    initial: ObligationForm,
    accounts: List<Choice<Long>>,
    expenseCategories: List<Choice<Long>>,
    incomeCategories: List<Choice<Long>>,
    onDismiss: () -> Unit,
    onSave: (ObligationForm) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var f by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "Nuevo pago o ingreso fijo" else "Editar") },
        text = {
            DialogColumn {
                ChoiceField(
                    label = "Tipo",
                    selected = f.isIncome,
                    choices = listOf(Choice(false, "Pago (arriendo, servicios…)"), Choice(true, "Ingreso (salario, arriendo recibido…)")),
                    onSelect = { f = f.copy(isIncome = it, categoryId = null) },
                )
                OutlinedTextField(f.name, { f = f.copy(name = it) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField(f.amountDigits, { f = f.copy(amountDigits = it) }, label = "Valor")
                OutlinedTextField(
                    f.day, { v -> f = f.copy(day = v.filter(Char::isDigit).take(2)) },
                    label = { Text("Día del mes") }, singleLine = true,
                    supportingText = { Text("Si el mes tiene menos días, se toma el último.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceField(
                    label = "Categoría (opcional)",
                    selected = f.categoryId ?: -1L,
                    choices = listOf(Choice(-1L, "Sin categoría")) + if (f.isIncome) incomeCategories else expenseCategories,
                    onSelect = { f = f.copy(categoryId = it.takeIf { id -> id != -1L }) },
                )
                ChoiceField(
                    label = "Cuenta habitual (opcional)",
                    selected = f.accountId ?: -1L,
                    choices = listOf(Choice(-1L, "Ninguna")) + accounts,
                    onSelect = { f = f.copy(accountId = it.takeIf { id -> id != -1L }) },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Activo", Modifier.weight(1f))
                    Switch(checked = f.active, onCheckedChange = { f = f.copy(active = it) })
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Eliminar") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(f) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
