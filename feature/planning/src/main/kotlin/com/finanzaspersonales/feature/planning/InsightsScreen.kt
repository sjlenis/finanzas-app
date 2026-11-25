package com.finanzaspersonales.feature.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
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
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.finance.Insight
import com.finanzaspersonales.engine.finance.InsightSeverity
import com.finanzaspersonales.engine.finance.PurchaseAnalysis
import com.finanzaspersonales.engine.finance.PurchaseVerdict

/** Análisis de hábitos, recomendaciones y simulador de compras. */
@Composable
fun InsightsRoute(onBack: () -> Unit, viewModel: InsightsViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val purchase by viewModel.purchase.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var priceDigits by remember { mutableStateOf("") }

    PlanningScaffold("Análisis y consejos", onBack) {
        item {
            FinanceCard(title = "¿Puedo comprarlo?") {
                Note("Escribe el precio. Uso tu dinero realmente disponible y tu gasto diario hasta el próximo ingreso; nunca cuento con tu fondo de emergencia ni tus metas.")
                AmountField(priceDigits, { priceDigits = it; viewModel.clearPurchase() }, label = "Precio")
                Button(onClick = { viewModel.simulate(priceDigits) }, enabled = priceDigits.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Analizar compra") }
                purchase?.let { PurchaseResult(it) }
            }
        }
        val r = report
        if (r == null) {
            item { Note("Analizando tus movimientos…") }
            return@PlanningScaffold
        }
        item { Text("Recomendaciones", style = MaterialTheme.typography.titleMedium) }
        if (r.insights.isEmpty()) {
            item {
                Note(
                    if (r.expensesAnalyzed < 10) "Aún hay pocos movimientos para analizar (${r.expensesAnalyzed}). Sigue registrando tus gastos y aquí aparecerán consejos con tus datos."
                    else "Vas bien: no veo alertas ni gastos fuera de lo normal.",
                )
            }
        }
        items(r.insights, key = { it.kind.name + it.title }) { InsightCard(it) }

        if (r.trends.isNotEmpty()) {
            item { Text("Comparado con tu promedio", style = MaterialTheme.typography.titleMedium) }
            items(r.trends, key = { "trend-${it.categoryId}" }) { t ->
                FinanceCard(title = t.name) {
                    LabeledMoney("Llevas este mes", t.thisMonth)
                    LabeledMoney("A este ritmo terminarías en", t.projected)
                    LabeledMoney("Tu promedio mensual", t.previousAverage)
                }
            }
        }
        r.ants?.let { a ->
            item {
                FinanceCard(title = "Gastos hormiga (últimos 30 días)") {
                    MoneyText(a.total, tone = MoneyTone.Expense)
                    Note("${a.count} gastos de ${a.threshold.formatCop()} o menos.")
                    a.top.forEach { (name, total) -> LabeledMoney(name, total) }
                }
            }
        }
        if (r.recurring.isNotEmpty()) {
            item { Text("Pagos que se repiten", style = MaterialTheme.typography.titleMedium) }
            items(r.recurring, key = { "rec-${it.name}" }) { rec ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rec.name, style = MaterialTheme.typography.titleSmall)
                            Note("En ${rec.months} meses, cerca del día ${rec.typicalDay}")
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(rec.averageAmount)
                            TextButton(onClick = { viewModel.addToCalendar(rec) }) { Text("Al calendario") }
                        }
                    }
                }
            }
        }
        if (r.unusual.isNotEmpty()) {
            item { Text("Gastos inusuales", style = MaterialTheme.typography.titleMedium) }
            items(r.unusual, key = { "un-${it.expense.id}" }) { u ->
                FinanceCard(title = u.expense.description) {
                    LabeledMoney("Valor", u.expense.amount)
                    LabeledMoney("Lo usual en ${u.expense.categoryName ?: "la categoría"}", u.usual)
                    Note("${u.expense.date.dayOfMonth}/${u.expense.date.monthValue}/${u.expense.date.year}")
                }
            }
        }
        item {
            Note(
                "Todo se calcula en tu teléfono con reglas fijas y tus movimientos confirmados" +
                    (if (r.monthsAnalyzed > 0) " (base: ${r.monthsAnalyzed.monthsLabel()})" else "") +
                    ". No se envía nada a internet.",
            )
        }
    }
    message?.let { MessageDialog(it, viewModel::clearMessage) }
}

@Composable
private fun InsightCard(insight: Insight) {
    val colors = when (insight.severity) {
        InsightSeverity.ALERT -> CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        InsightSeverity.WARNING -> CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        InsightSeverity.TIP -> CardDefaults.elevatedCardColors()
    }
    ElevatedCard(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(insight.title, style = MaterialTheme.typography.titleSmall)
            Text(insight.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PurchaseResult(a: PurchaseAnalysis) {
    val (title, color) = when (a.verdict) {
        PurchaseVerdict.YES -> "Sí puedes comprarlo" to MaterialTheme.colorScheme.primary
        PurchaseVerdict.CAREFUL -> "Puedes, pero quedarías justo" to MaterialTheme.colorScheme.tertiary
        PurchaseVerdict.WAIT -> "Mejor espera" to MaterialTheme.colorScheme.error
    }
    Text(title, style = MaterialTheme.typography.titleMedium, color = color)
    LabeledMoney("Disponible hoy", a.available)
    if (a.dailyNeeds.centavos > 0) LabeledMoney("Día a día hasta tu ingreso (${a.daysUntilIncome} días)", a.dailyNeeds)
    LabeledMoney("Te quedaría libre", a.leftAfter)
    a.reasons.drop(1).forEach { Note(it) }
}
