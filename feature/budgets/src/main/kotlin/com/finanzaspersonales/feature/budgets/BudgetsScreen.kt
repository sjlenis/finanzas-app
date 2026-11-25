package com.finanzaspersonales.feature.budgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.finanzaspersonales.core.data.BudgetLine
import com.finanzaspersonales.core.data.CashOverview
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import com.finanzaspersonales.core.designsystem.component.formatShortDate
import com.finanzaspersonales.core.designsystem.theme.AmountLarge
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.finance.BudgetLevel
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthName = DateTimeFormatter.ofPattern("MMMM", Locale.forLanguageTag("es-CO"))

@Composable
fun BudgetsRoute(onCalendar: () -> Unit = {}, viewModel: BudgetsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val recommendation by viewModel.recommendation.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BudgetLine?>(null) }
    var adding by remember { mutableStateOf(false) }
    val o = state.overview
    if (o == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { AvailableCard(o, onCalendar) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Presupuestos de ${o.today.format(monthName)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::suggest) { Text("Sugerir") }
                TextButton(onClick = { adding = true }) { Text("Agregar") }
            }
        }
        if (o.budgets.isEmpty()) {
            item {
                Text(
                    "Aún no tienes presupuestos. Toca \"Sugerir\" para recibir montos basados en tu gasto real, " +
                        "o \"Agregar\" para ponerlos tú. Te aviso aquí y en Inicio cuando llegues al 80 %.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(o.budgets, key = { it.budget.id }) { line -> BudgetRow(line) { editing = line } }
    }

    if (adding || editing != null) {
        val line = editing
        var categoryId by remember { mutableStateOf(line?.budget?.categoryId) }
        var digits by remember { mutableStateOf(line?.budget?.limit?.pesos?.toString().orEmpty()) }
        val close = { adding = false; editing = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(if (line == null) "Nuevo presupuesto" else line.budget.categoryName) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (line == null) {
                        ChoiceField("Categoría", categoryId, state.categories.map { Choice(it.id, it.name) }, { categoryId = it })
                    }
                    AmountField(digits, { digits = it }, label = "Límite mensual")
                    if (line != null) TextButton(onClick = { viewModel.deleteBudget(line.budget.id); close() }) { Text("Quitar presupuesto") }
                }
            },
            confirmButton = {
                TextButton(enabled = categoryId != null, onClick = { categoryId?.let { viewModel.setBudget(it, digits) { close() } } }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } },
        )
    }

    recommendation?.let { r ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSuggestions,
            title = { Text("Presupuesto sugerido") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Basado en tu gasto promedio de los últimos meses. Protege los gastos esenciales y, si no alcanza, recorta primero los demás.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    r.suggestions.forEach { s ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.name + if (s.essential) " (esencial)" else "", style = MaterialTheme.typography.bodyMedium)
                            MoneyText(s.suggested)
                        }
                        if (s.suggested != s.average) Text("Promedio actual: ${s.average.formatCop()}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Disponible para gastos", style = MaterialTheme.typography.bodyMedium)
                        MoneyText(r.availableForSpending)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sin asignar (ahorro e imprevistos)", style = MaterialTheme.typography.bodyMedium)
                        MoneyText(r.unallocated, tone = MoneyTone.Signed)
                    }
                    r.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = r.suggestions.isNotEmpty(), onClick = viewModel::applySuggestions) { Text("Aplicar") } },
            dismissButton = { TextButton(onClick = viewModel::dismissSuggestions) { Text("Cerrar") } },
        )
    }
    message?.let {
        AlertDialog(onDismissRequest = viewModel::clearMessage, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("Entendido") } })
    }
}

@Composable
private fun AvailableCard(o: CashOverview, onCalendar: () -> Unit) {
    FinanceCard(title = "Dinero realmente disponible") {
        MoneyText(o.money.available, style = AmountLarge, tone = if (o.money.available.isNegative) MoneyTone.Signed else MoneyTone.Neutral)
        Line("Dinero en cuentas", o.money.total)
        Line("− Pagos pendientes del mes", o.money.obligations)
        Line("− Fondo de emergencia", o.money.emergencyFund)
        Line("− Metas de ahorro", o.money.goals)
        if (o.money.available.isNegative) {
            Text("Te falta dinero para cubrir lo comprometido. Revisa tus pagos pendientes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            val until = o.nextIncome?.let { "hasta tu próximo ingreso (${formatShortDate(it)})" } ?: "hasta fin de mes"
            Text("Puedes gastar unos ${o.weeklyAllowance.formatCop()} por semana $until.", style = MaterialTheme.typography.bodyMedium)
        }
        Line("Proyección a fin de mes", o.projectedEndOfMonth)
        Text(
            "Proyección = dinero de hoy + ingresos previstos − pagos pendientes − tu gasto diario promedio por los días que faltan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCalendar, modifier = Modifier.fillMaxWidth()) { Text("Calendario de pagos e ingresos") }
    }
}

@Composable
private fun Line(label: String, money: Money) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(money)
    }
}

@Composable
private fun BudgetRow(line: BudgetLine, onClick: () -> Unit) {
    val s = line.status
    val color = when (s.level) {
        BudgetLevel.OK -> MaterialTheme.colorScheme.primary
        BudgetLevel.NEAR -> MaterialTheme.colorScheme.tertiary
        BudgetLevel.OVER -> MaterialTheme.colorScheme.error
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(line.budget.categoryName, style = MaterialTheme.typography.bodyLarge)
            Text("${s.spent.formatCop()} de ${s.limit.formatCop()}", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(progress = { s.percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth(), color = color)
        val text = when (s.level) {
            BudgetLevel.OK -> "Te quedan ${s.remaining.formatCop()}"
            BudgetLevel.NEAR -> "Vas en ${s.percent} %: te quedan ${s.remaining.formatCop()}"
            BudgetLevel.OVER -> "Te pasaste por ${(-s.remaining).formatCop()}"
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (s.level == BudgetLevel.OK) MaterialTheme.colorScheme.onSurfaceVariant else color)
    }
}
