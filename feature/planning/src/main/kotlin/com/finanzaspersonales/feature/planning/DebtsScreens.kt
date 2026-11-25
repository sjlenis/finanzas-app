package com.finanzaspersonales.feature.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.data.Debt
import com.finanzaspersonales.core.data.DebtStatus
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.theme.AmountLarge
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.DateField
import com.finanzaspersonales.core.designsystem.component.EmptyState
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.formatShortDate
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.finance.DebtPlan
import com.finanzaspersonales.engine.finance.RateType

private fun Debt.rateLabel(): String = when (rateType) {
    RateType.NONE -> "Sin interés"
    else -> java.math.BigDecimal(rateBasisPoints).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ',') +
        " % " + if (rateType == RateType.EFFECTIVE_ANNUAL) "E.A." else "M.V."
}

@Composable
fun DebtsRoute(onBack: () -> Unit, onOpen: (Long) -> Unit, onPlan: () -> Unit, viewModel: DebtsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    PlanningScaffold("Deudas", onBack, fabLabel = "Nueva deuda", onFab = { adding = true }) {
        if (!state.loading && state.debts.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.CreditCard,
                    title = "Sin deudas registradas",
                    message = "Registra tus tarjetas, créditos o préstamos para ver cuándo quedarás libre de deudas " +
                        "y qué estrategia te ahorra más intereses.",
                )
            }
            return@PlanningScaffold
        }
        item {
            FinanceCard(title = "Total que debes") {
                MoneyText(state.totalBalance, style = AmountLarge)
                LabeledMoney("Cuotas mensuales", state.totalMonthly)
                Button(onClick = onPlan, enabled = state.active.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Ver plan para salir de deudas")
                }
            }
        }
        items(state.debts, key = { it.id }) { debt -> DebtCard(debt, onClick = { onOpen(debt.id) }) }
    }
    if (adding) DebtFormDialog("Nueva deuda", DebtForm(), onDismiss = { adding = false }) { viewModel.save(null, it) { adding = false } }
    message?.let { MessageDialog(it, viewModel::clearMessage) }
}

@Composable
private fun DebtCard(debt: Debt, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(debt.name, style = MaterialTheme.typography.titleMedium)
                MoneyText(debt.balance)
            }
            Note(
                listOfNotNull(
                    debt.creditor,
                    debt.rateLabel(),
                    "Cuota ${debt.monthlyPayment.formatCop()}",
                    debt.dueDay?.let { "día $it" },
                    if (debt.status == DebtStatus.PAID) "Pagada" else null,
                ).joinToString(" · "),
            )
            Progress(debt.paidPercent)
            Note("${debt.paidPercent} % pagado de ${debt.initial.formatCop()}")
        }
    }
}

@Composable
fun DebtDetailRoute(onBack: () -> Unit, viewModel: DebtDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var paying by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val debt = state.debt
    PlanningScaffold(debt?.name ?: "Deuda", onBack) {
        if (debt == null) return@PlanningScaffold
        item {
            FinanceCard(title = "Saldo pendiente") {
                MoneyText(debt.balance, style = AmountLarge)
                Progress(debt.paidPercent)
                LabeledMoney("Valor inicial", debt.initial)
                LabeledText("Tasa", debt.rateLabel())
                LabeledMoney("Cuota mensual", debt.monthlyPayment)
                debt.dueDay?.let { LabeledText("Día de pago", "$it de cada mes") }
                debt.remainingInstallments?.let { LabeledText("Cuotas pendientes", it.toString()) }
                debt.creditor?.let { LabeledText("Entidad o persona", it) }
                debt.notes?.let { Note(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { paying = true }, enabled = debt.status == DebtStatus.ACTIVE) { Text("Registrar pago") }
                    OutlinedButton(onClick = { editing = true }) { Text("Editar") }
                    TextButton(onClick = { deleting = true }) { Text("Eliminar") }
                }
            }
        }
        item { Text("Pagos", style = MaterialTheme.typography.titleMedium) }
        if (state.payments.isEmpty()) item { Note("Aún no hay pagos registrados.") }
        items(state.payments, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(formatShortDate(BogotaTime.dateOf(p.paidAt)), style = MaterialTheme.typography.bodyMedium)
                    Note("Saldo después: ${p.balanceAfter.formatCop()}")
                }
                MoneyText(p.amount)
            }
        }
    }
    if (paying && debt != null) {
        var amount by remember { mutableStateOf(debt.monthlyPayment.toDigits()) }
        var accountId by remember { mutableStateOf<Long?>(state.accounts.firstOrNull()?.id) }
        var newBalance by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(state.today) }
        AlertDialog(
            onDismissRequest = { paying = false },
            title = { Text("Registrar pago") },
            text = {
                DialogColumn {
                    AmountField(amount, { amount = it }, label = "Valor pagado")
                    ChoiceField(
                        label = "Pagado desde",
                        selected = accountId ?: -1L,
                        choices = listOf(Choice(-1L, "No descontar de ninguna cuenta")) + state.accounts.map { Choice(it.id, it.name) },
                        onSelect = { accountId = it.takeIf { id -> id != -1L } },
                    )
                    AmountField(newBalance, { newBalance = it }, label = "Saldo después del pago (opcional)",
                        supportingText = "Si tu banco te dice el saldo nuevo, escríbelo: así se incluyen los intereses.")
                    DateField(date, { date = it }, maxDate = state.today)
                    Note("El pago no cuenta como gasto: reduce lo que debes.")
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.pay(amount, accountId, newBalance, date) { paying = false } }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { paying = false }) { Text("Cancelar") } },
        )
    }
    if (editing && debt != null) DebtFormDialog("Editar deuda", DebtForm.from(debt), onDismiss = { editing = false }) { viewModel.save(it) { editing = false } }
    if (deleting) ConfirmDialog("¿Eliminar la deuda?", "Se borra la deuda y su historial de pagos. Los movimientos de tus cuentas se conservan.", "Eliminar", { viewModel.delete(onBack) }) { deleting = false }
    message?.let { MessageDialog(it, viewModel::clearMessage) }
}

@Composable
private fun DebtFormDialog(title: String, initial: DebtForm, onDismiss: () -> Unit, onSave: (DebtForm) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            DialogColumn {
                OutlinedTextField(f.name, { f = f.copy(name = it.take(40)) }, label = { Text("Nombre (ej.: Tarjeta Visa)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.creditor, { f = f.copy(creditor = it.take(40)) }, label = { Text("Entidad o persona") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField(f.initialDigits, { f = f.copy(initialDigits = it) }, label = "Valor inicial")
                AmountField(f.balanceDigits, { f = f.copy(balanceDigits = it) }, label = "Saldo pendiente hoy",
                    supportingText = "Déjalo vacío si es igual al valor inicial")
                ChoiceField("Tipo de tasa", f.rateType, RateType.entries.map { Choice(it, it.label) }, { f = f.copy(rateType = it) })
                if (f.rateType != RateType.NONE) {
                    OutlinedTextField(
                        f.rateText, { f = f.copy(rateText = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(6)) },
                        label = { Text("Tasa de interés (%)") }, singleLine = true, isError = f.rateBasisPoints == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    )
                }
                AmountField(f.paymentDigits, { f = f.copy(paymentDigits = it) }, label = "Cuota mensual")
                OutlinedTextField(f.dueDay, { f = f.copy(dueDay = it.filter(Char::isDigit).take(2)) }, label = { Text("Día de pago (1-31, opcional)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.installments, { f = f.copy(installments = it.filter(Char::isDigit).take(3)) }, label = { Text("Cuotas pendientes (opcional)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.notes, { f = f.copy(notes = it.take(200)) }, label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(f) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun DebtPlanRoute(onBack: () -> Unit, viewModel: DebtPlanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlanningScaffold("Plan para salir de deudas", onBack) {
        val c = state.comparison
        if (!state.loading && c == null) {
            item { Note("No tienes deudas activas.") }
            return@PlanningScaffold
        }
        if (c == null) return@PlanningScaffold
        item {
            FinanceCard(title = "Pago extra al mes") {
                AmountField(state.extraDigits, viewModel::setExtra, label = "Además de las cuotas (opcional)")
                LabeledMoney("Necesitas cada mes", c.recommended.monthlyBudget)
                state.surplusBeforeDebts?.let { surplus ->
                    if ((state.profile?.monthsAnalyzed ?: 0) > 0) {
                        LabeledMoney("Te sobra al mes antes de deudas", surplus)
                        if (c.recommended.monthlyBudget > surplus) {
                            Note(
                                "Este plan pide más de lo que te sobra en promedio. No uses todo tu dinero en deudas: " +
                                    "protege tus gastos básicos y deja algo para imprevistos.",
                                error = true,
                            )
                        }
                    }
                }
            }
        }
        item { PlanCard(c.recommended, recommended = true) }
        val other = if (c.recommended === c.avalanche) c.snowball else c.avalanche
        item { PlanCard(other, recommended = false) }
        item {
            FinanceCard(title = "Solo cuotas mínimas") {
                PlanSummary(c.minimumOnly)
                c.interestSaved?.takeIf { it.centavos > 0 }?.let {
                    Text("Con el plan recomendado te ahorras ${it.formatCop()} en intereses.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Note(
                "Cálculo mes a mes con tus tasas y cuotas actuales. Es una estimación: la entidad puede cobrar seguros, " +
                    "comisiones o calcular intereses de otra forma. Actualiza el saldo con cada pago para mantenerlo exacto.",
            )
        }
    }
}

@Composable
private fun PlanCard(plan: DebtPlan, recommended: Boolean) {
    val strategy = plan.strategy ?: return
    FinanceCard(title = if (recommended) "${strategy.label} (recomendado)" else strategy.label) {
        Note(strategy.explanation)
        PlanSummary(plan)
        if (plan.payoffs.isNotEmpty()) {
            Text("Orden de pago", style = MaterialTheme.typography.labelLarge)
            plan.payoffs.forEachIndexed { i, p -> Note("${i + 1}. ${p.name}: ${p.month.label()}") }
        }
    }
}

@Composable
private fun PlanSummary(plan: DebtPlan) {
    if (plan.feasible) {
        LabeledText("Libre de deudas en", plan.months!!.monthsLabel())
        plan.debtFreeMonth?.let { LabeledText("Fecha estimada", it.label()) }
        LabeledMoney("Intereses totales", plan.totalInterest)
        LabeledMoney("Total a pagar", plan.totalPaid)
    }
    plan.problems.forEach { Note(it, error = true) }
}
