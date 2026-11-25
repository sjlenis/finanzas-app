package com.finanzaspersonales.feature.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.data.GoalStatus
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.component.DateField
import com.finanzaspersonales.core.designsystem.component.EmptyState
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.formatShortDate
import com.finanzaspersonales.core.designsystem.theme.AmountLarge
import com.finanzaspersonales.core.model.formatCop
import java.time.LocalDate

private const val RESERVE_NOTE = "El dinero sigue en tus cuentas: la meta solo lo aparta para que no lo cuentes como disponible."

@Composable
fun GoalsRoute(onBack: () -> Unit, viewModel: GoalsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GoalItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Pair<GoalItem, Boolean>?>(null) }
    var deleting by remember { mutableStateOf<GoalItem?>(null) }

    PlanningScaffold("Metas de ahorro", onBack, fabLabel = "Nueva meta", onFab = { adding = true }) {
        if (!state.loading && state.goals.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Savings,
                    title = "Sin metas todavía",
                    message = "Crea metas como un computador, un viaje o tus estudios. Te digo cuánto ahorrar " +
                        "y si cabe en tu presupuesto sin afectar tus obligaciones.",
                )
            }
            return@PlanningScaffold
        }
        state.profile?.let { p ->
            item {
                Note(
                    if (p.monthsAnalyzed == 0) "Aún no hay meses con movimientos para calcular tu excedente."
                    else "Tu excedente promedio (ingresos − gastos − cuotas de deudas): ${p.monthlySurplus.formatCop()} al mes.",
                )
            }
        }
        items(state.goals, key = { it.goal.id }) { item ->
            val g = item.goal
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium)
                        Text("${item.plan.percent} %", style = MaterialTheme.typography.titleMedium)
                    }
                    g.description?.let { Note(it) }
                    Progress(item.plan.percent)
                    Note("${g.saved.formatCop()} de ${g.target.formatCop()}" + (g.deadline?.let { " · hasta ${formatShortDate(it)}" } ?: ""))
                    Text(
                        item.plan.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.plan.feasible == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    item.plan.requiredWeekly?.takeIf { it.centavos > 0 && g.status != GoalStatus.ACHIEVED }?.let { Note("Equivale a ${it.formatCop()} por semana.") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { moving = item to false }) { Text("Aportar") }
                        OutlinedButton(onClick = { moving = item to true }, enabled = g.saved.centavos > 0) { Text("Retirar") }
                        TextButton(onClick = { editing = item }) { Text("Editar") }
                    }
                }
            }
        }
        item { Note(RESERVE_NOTE) }
    }

    if (adding || editing != null) {
        val g = editing?.goal
        var name by remember { mutableStateOf(g?.name.orEmpty()) }
        var description by remember { mutableStateOf(g?.description.orEmpty()) }
        var target by remember { mutableStateOf(g?.target.toDigits()) }
        var hasDeadline by remember { mutableStateOf(g?.deadline != null) }
        var deadline by remember { mutableStateOf(g?.deadline ?: state.today.plusMonths(6)) }
        val close = { adding = false; editing = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(if (g == null) "Nueva meta" else "Editar meta") },
            text = {
                DialogColumn {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Nombre (ej.: Computador)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it.take(120) }, label = { Text("Descripción (opcional)") }, modifier = Modifier.fillMaxWidth())
                    AmountField(target, { target = it }, label = "Valor objetivo")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(hasDeadline, { hasDeadline = it })
                        Text("Con fecha límite")
                    }
                    if (hasDeadline) DateField(deadline, { deadline = it }, label = "Fecha límite")
                    if (g != null) TextButton(onClick = { deleting = editing; close() }) { Text("Eliminar meta") }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.save(g?.id, name, description, target, deadline.takeIf { hasDeadline }) { close() } }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } },
        )
    }
    moving?.let { (item, withdraw) ->
        AmountDialog(
            title = if (withdraw) "Retirar de ${item.goal.name}" else "Aportar a ${item.goal.name}",
            note = RESERVE_NOTE,
            onDismiss = { moving = null },
        ) { viewModel.contribute(item.goal.id, it, withdraw) { moving = null } }
    }
    deleting?.let { item ->
        ConfirmDialog("¿Eliminar ${item.goal.name}?", "Se borra la meta y sus aportes. El dinero de tus cuentas no cambia.", "Eliminar",
            { viewModel.delete(item.goal.id) {} }) { deleting = null }
    }
    message?.let { MessageDialog(it, viewModel::clearMessage) }
}

@Composable
private fun AmountDialog(title: String, note: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { DialogColumn { AmountField(amount, { amount = it }); Note(note) } },
        confirmButton = { TextButton(onClick = { onConfirm(amount) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun EmergencyFundRoute(onBack: () -> Unit, viewModel: EmergencyFundViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var configuring by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Boolean?>(null) }
    var emptying by remember { mutableStateOf(false) }
    val plan = state.plan
    val fund = state.fund

    PlanningScaffold("Fondo de emergencia", onBack) {
        if (plan == null || fund == null) return@PlanningScaffold
        item {
            FinanceCard(title = "Ahorrado") {
                MoneyText(plan.saved, style = AmountLarge)
                Progress(plan.percent)
                Note("${plan.percent} % de ${plan.target.formatCop()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { moving = false }) { Text("Aportar") }
                    OutlinedButton(onClick = { moving = true }, enabled = plan.saved.centavos > 0) { Text("Usar fondo") }
                }
                if (plan.saved.centavos > 0) {
                    TextButton(onClick = { emptying = true }) { Text("Vaciar fondo (dejar en \$0)") }
                }
            }
        }
        item {
            FinanceCard(title = "¿Cuenta como gasto?") {
                Note(
                    "No. Aportar al fondo no saca dinero de tus cuentas ni crea un gasto: solo marca una parte de tu saldo " +
                        "como reservada. Por eso en Inicio la ves restada del dinero disponible, pero el total en cuentas no cambia.",
                )
                Note(
                    "Si además registraste ese aporte como un gasto en Movimientos o en el asistente, el dinero se descuenta dos veces. " +
                        "En ese caso borra ese gasto en Movimientos.",
                )
            }
        }
        item {
            FinanceCard(title = "Meta") {
                LabeledMoney("Gastos esenciales al mes", plan.essentialMonthly)
                Note("Promedio de tus gastos en categorías esenciales más las cuotas de tus deudas.")
                LabeledText("Meses cubiertos", plan.targetMonths.monthsLabel())
                LabeledMoney(if (fund.customTarget) "Meta (fijada por ti)" else "Meta recomendada", plan.target)
                plan.milestones.forEach { (label, amount) ->
                    val done = plan.saved >= amount && amount.centavos > 0
                    LabeledText((if (done) "✓ " else "") + label, amount.formatCop())
                }
                TextButton(onClick = { configuring = true }) { Text("Cambiar meta y aporte") }
            }
        }
        item {
            FinanceCard(title = "Cómo llegar") {
                if (fund.plannedMonthly != null) {
                    LabeledMoney("Tu aporte mensual", fund.plannedMonthly!!)
                }
                LabeledMoney("Aporte sugerido al mes", plan.suggestedMonthly)
                LabeledMoney("Equivale por semana", plan.suggestedWeekly)
                plan.eta?.let { LabeledText("Lo lograrías en", it.label()) }
                Note("La sugerencia usa el ${com.finanzaspersonales.engine.finance.EmergencyFund.SHARE_OF_SURPLUS_PERCENT} % de tu excedente mensual, para no afectar tus obligaciones ni endeudarte.")
                plan.notes.forEach { Note(it, error = true) }
            }
        }
        item { Note("El fondo de emergencia es aparte de tus metas de ahorro y no se cuenta como dinero disponible. $RESERVE_NOTE") }
    }

    if (configuring && fund != null && plan != null) {
        var months by remember { mutableStateOf(plan.targetMonths) }
        var custom by remember { mutableStateOf(fund.customTarget) }
        var target by remember { mutableStateOf(if (fund.customTarget) fund.target.toDigits() else "") }
        var planned by remember { mutableStateOf(fund.plannedMonthly.toDigits()) }
        AlertDialog(
            onDismissRequest = { configuring = false },
            title = { Text("Meta del fondo") },
            text = {
                DialogColumn {
                    Text("Meses de gastos esenciales", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (3..6).forEach { m -> FilterChip(selected = months == m && !custom, onClick = { months = m; custom = false }, label = { Text("$m") }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(custom, { custom = it })
                        Text("Fijar la meta a mano")
                    }
                    if (custom) AmountField(target, { target = it }, label = "Meta del fondo")
                    AmountField(planned, { planned = it }, label = "Aporte mensual que harás (opcional)",
                        supportingText = "Si lo dejas vacío uso el aporte sugerido")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.configure(months, if (custom) target else null, planned) { configuring = false } }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { configuring = false }) { Text("Cancelar") } },
        )
    }
    moving?.let { withdraw ->
        AmountDialog(
            title = if (withdraw) "Usar el fondo" else "Aportar al fondo",
            note = if (withdraw) "Úsalo solo para emergencias reales: salud, pérdida de ingresos o daños urgentes." else RESERVE_NOTE,
            onDismiss = { moving = null },
        ) { viewModel.contribute(it, withdraw) { moving = null } }
    }
    if (emptying && plan != null) {
        AlertDialog(
            onDismissRequest = { emptying = false },
            title = { Text("¿Vaciar el fondo?") },
            text = {
                Text(
                    "El fondo quedará en \$0 (hoy tiene ${plan.saved.formatCop()}). Tus cuentas no cambian, porque el dinero nunca salió de ellas: " +
                        "solo deja de estar apartado y vuelve a contar como disponible. Tu meta y tu aporte mensual se mantienen.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.empty { emptying = false } }) { Text("Vaciar") } },
            dismissButton = { TextButton(onClick = { emptying = false }) { Text("Cancelar") } },
        )
    }
    message?.let { MessageDialog(it, viewModel::clearMessage) }
}
