package com.finanzaspersonales.feature.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.OverviewRepository
import com.finanzaspersonales.core.data.StatisticsRepository
import com.finanzaspersonales.core.data.StatsReport
import com.finanzaspersonales.core.designsystem.component.BarChart
import com.finanzaspersonales.core.designsystem.component.BarGroup
import com.finanzaspersonales.core.designsystem.component.ChartTable
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.LineChart
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import com.finanzaspersonales.core.designsystem.theme.AmountLarge
import com.finanzaspersonales.core.model.formatCop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(overview: OverviewRepository, stats: StatisticsRepository) : ViewModel() {
    /** Se recalcula cuando cambian tus cuentas o movimientos. */
    val report: StateFlow<StatsReport?> = overview.observe().mapLatest { stats.report() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private val shortMonth = DateTimeFormatter.ofPattern("MMM", esCO)
private fun YearMonth.short(): String = format(shortMonth).removeSuffix(".").replaceFirstChar { it.uppercase() }

@Composable
fun StatisticsRoute(onBack: () -> Unit, viewModel: StatisticsViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    PlanningScaffold("Estadísticas", onBack) {
        val r = report
        if (r == null) {
            item { Note("Calculando…") }
            return@PlanningScaffold
        }
        item {
            FinanceCard(title = "Patrimonio neto") {
                MoneyText(r.netWorth.net, style = AmountLarge, tone = MoneyTone.Signed)
                LabeledMoney("Lo que tienes (todas tus cuentas)", r.netWorth.assets)
                LabeledMoney("Lo que debes (deudas activas)", r.netWorth.liabilities)
                Note("Tus metas y el fondo de emergencia ya están dentro de tus cuentas, por eso no se suman aparte.")
            }
        }
        val hasMovements = r.months.any { it.income.centavos != 0L || it.expense.centavos != 0L }
        if (!hasMovements) {
            item { Note("Aún no hay ingresos ni gastos registrados en los últimos meses. Las gráficas aparecerán cuando registres movimientos.") }
        } else {
            item {
                FinanceCard(title = "Ingresos frente a gastos") {
                    BarChart(
                        groups = r.months.map { BarGroup(it.month.short(), listOf(it.income, it.expense)) },
                        seriesNames = listOf("Ingresos", "Gastos"),
                    )
                    ChartTable(
                        listOf("Mes", "Ingresos", "Gastos"),
                        r.months.map { listOf(it.month.label(), it.income.formatCop(), it.expense.formatCop()) },
                    )
                }
            }
            item {
                FinanceCard(title = "Flujo de caja") {
                    Note("Lo que te quedó cada mes: ingresos menos gastos. No incluye transferencias entre tus cuentas ni préstamos.")
                    BarChart(
                        groups = r.months.map { BarGroup(it.month.short(), listOf(it.net)) },
                        seriesNames = listOf("Te quedó"),
                        signedColors = true,
                    )
                    ChartTable(listOf("Mes", "Te quedó"), r.months.map { listOf(it.month.label(), it.net.formatCop()) })
                }
            }
        }
        if (r.categoriesThisMonth.isNotEmpty()) {
            item {
                FinanceCard(title = "Gastos por categoría este mes") {
                    val max = r.categoriesThisMonth.maxOf { it.amount.centavos }.toFloat().coerceAtLeast(1f)
                    r.categoriesThisMonth.take(10).forEach { c ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            LabeledMoney(c.categoryName, c.amount)
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(
                                    Modifier.fillMaxWidth(c.amount.centavos / max).height(8.dp).clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (r.comparison.isNotEmpty()) {
            item {
                FinanceCard(title = "Este mes frente al anterior") {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Categoría", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
                        Text("Este mes", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Text("Anterior", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    }
                    r.comparison.take(10).forEach { c ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(c.name, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                            Text(c.thisMonth.formatCop(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(c.lastMonth.formatCop(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Note("El mes actual aún no termina: compáralo con cuidado.")
                }
            }
        }
        item {
            FinanceCard(title = "Evolución del ahorro") {
                if (!r.hasSavings) Note("Aún no hay aportes a metas ni al fondo de emergencia.")
                else {
                    LineChart(r.savings.map { it.first.short() to it.second }, "Ahorrado")
                    ChartTable(listOf("Mes", "Ahorrado"), r.savings.map { listOf(it.first.label(), it.second.formatCop()) })
                }
            }
        }
        item {
            FinanceCard(title = "Evolución de las deudas") {
                if (!r.hasDebts) Note("No tienes deudas registradas.")
                else {
                    LineChart(r.debt.map { it.first.short() to it.second }, "Deuda total")
                    ChartTable(listOf("Mes", "Deuda total"), r.debt.map { listOf(it.first.label(), it.second.formatCop()) })
                    Note("Según los pagos que registraste en cada deuda.")
                }
            }
        }
        item { Note("Toca una barra o un punto para ver su valor exacto. Todo se calcula en tu teléfono.") }
    }
}
