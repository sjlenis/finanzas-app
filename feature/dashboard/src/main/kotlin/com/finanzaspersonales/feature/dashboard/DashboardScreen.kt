package com.finanzaspersonales.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import com.finanzaspersonales.core.designsystem.theme.AmountLarge
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.finance.BudgetLevel
import com.finanzaspersonales.engine.finance.BudgetStatus

@Composable
fun DashboardRoute(
    onOpenAccount: (Long) -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenCalendar: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(state, onOpenAccount, onOpenSecurity, onOpenPending, onOpenCalendar, onOpenBudgets, onOpenInsights)
}

/**
 * Panel de inicio. Solo muestra datos reales de la base de datos. Las tarjetas de
 * secciones aún no construidas lo dicen claramente en vez de mostrar cifras inventadas.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onOpenAccount: (Long) -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenPending: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.pendingCount > 0) {
            item {
                FinanceCard(title = "Movimientos por revisar") {
                    Text(
                        if (state.pendingCount == 1) "Detecté 1 movimiento en tus notificaciones. No cuenta hasta que lo confirmes."
                        else "Detecté ${state.pendingCount} movimientos en tus notificaciones. No cuentan hasta que los confirmes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenPending) { Text("Revisar") }
                }
            }
        }
        if (!state.hasPin) {
            item {
                FinanceCard(title = "Protege tu información") {
                    Text("Crea un PIN para que nadie más pueda abrir tus finanzas.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenSecurity) { Text("Crear PIN") }
                }
            }
        }
        item {
            FinanceCard(title = "Dinero total en cuentas") {
                MoneyText(state.total, style = AmountLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("En efectivo", style = MaterialTheme.typography.bodyMedium)
                    MoneyText(state.cash)
                }
            }
        }
        state.overview?.let { o ->
            item {
                FinanceCard(title = "Dinero realmente disponible") {
                    MoneyText(o.money.available, style = AmountLarge, tone = MoneyTone.Signed)
                    LabelRow("Pagos pendientes del mes", o.money.obligations)
                    if (o.money.emergencyFund.centavos > 0) LabelRow("Apartado en el fondo de emergencia", o.money.emergencyFund)
                    if (o.money.goals.centavos > 0) LabelRow("Apartado en metas", o.money.goals)
                    if (o.money.available.isNegative) {
                        Text(
                            "Lo que tienes no alcanza para cubrir lo pendiente y lo apartado. Revisa tus pagos o usa parte del ahorro solo si es necesario.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (o.weeklyAllowance.centavos > 0) {
                        LabelRow(if (o.nextIncome != null) "Para gastar por semana hasta tu próximo ingreso" else "Para gastar por semana hasta fin de mes", o.weeklyAllowance)
                    }
                }
            }
            item {
                FinanceCard(title = "Próximos pagos") {
                    val next = o.pending.take(4)
                    if (next.isEmpty()) {
                        Text(
                            if (o.monthItems.isEmpty()) "Agrega tus pagos fijos e ingresos en el calendario para verlos aquí."
                            else "No te quedan pagos pendientes este mes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    next.forEach { p ->
                        val late = p.date.isBefore(o.today)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    (if (late) "Vencido · " else "") + com.finanzaspersonales.core.designsystem.component.formatShortDate(p.date),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            MoneyText(p.amount)
                        }
                    }
                    LabelRow("Saldo estimado a fin de mes", o.projectedEndOfMonth, MoneyTone.Signed)
                    TextButton(onClick = onOpenCalendar) { Text("Ver calendario") }
                }
            }
        }
        if (state.insights.isNotEmpty()) {
            item {
                FinanceCard(title = "Consejos para ti") {
                    state.insights.take(2).forEach { tip ->
                        Text(
                            tip.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (tip.severity == com.finanzaspersonales.engine.finance.InsightSeverity.ALERT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(tip.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onOpenInsights) { Text(if (state.insights.size > 2) "Ver los ${state.insights.size} consejos" else "Ver análisis") }
                }
            }
        }
        item {
            FinanceCard(title = "Este mes") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Ingresos", style = MaterialTheme.typography.labelLarge)
                        MoneyText(state.month.income, tone = MoneyTone.Income)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Gastos", style = MaterialTheme.typography.labelLarge)
                        MoneyText(state.month.expense, tone = MoneyTone.Expense)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Balance del mes", style = MaterialTheme.typography.bodyMedium)
                    MoneyText(state.month.net, tone = MoneyTone.Signed)
                }
            }
        }
        item {
            FinanceCard(title = "Saldo por cuenta") {
                state.accounts.forEachIndexed { index, account ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenAccount(account.id) }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(account.name, style = MaterialTheme.typography.bodyLarge)
                        MoneyText(account.balance)
                    }
                }
                if (state.accounts.all { it.balance == Money.ZERO }) {
                    Text(
                        "Toca una cuenta para registrar su saldo inicial o conciliarla con tu banco.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SpendingCard(state) }
        item {
            FinanceCard(title = "Deudas y ahorro") {
                if (!state.hasPlanning) {
                    Text(
                        "Registra tus deudas, metas y fondo de emergencia desde Perfil para verlos aquí.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total de deudas", style = MaterialTheme.typography.bodyMedium)
                        MoneyText(state.debtTotal)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Ahorrado en metas", style = MaterialTheme.typography.bodyMedium)
                        MoneyText(state.savedInGoals)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Fondo de emergencia", style = MaterialTheme.typography.bodyMedium)
                        MoneyText(state.emergencySaved)
                    }
                    if (state.emergencyTarget.centavos > 0) {
                        val pct = (state.emergencySaved.centavos * 100 / state.emergencyTarget.centavos).toInt().coerceIn(0, 100)
                        androidx.compose.material3.LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$pct % de la meta del fondo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        state.overview?.let { o ->
            item {
                FinanceCard(title = "Presupuestos") {
                    if (o.budgets.isEmpty()) {
                        Text("Aún no tienes presupuestos. Créalos en la pestaña Presupuestos.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val alerts = o.budgets.filter { it.status.level != BudgetLevel.OK }
                        if (alerts.isEmpty()) {
                            Text("Vas bien: ningún presupuesto pasa del ${BudgetStatus.NEAR_PERCENT} %.", style = MaterialTheme.typography.bodyMedium)
                        }
                        alerts.take(4).forEach { line ->
                            val over = line.status.level == BudgetLevel.OVER
                            Text(
                                if (over) "${line.budget.categoryName}: te pasaste por ${(line.status.spent - line.status.limit).formatCop()}"
                                else "${line.budget.categoryName}: llevas el ${line.status.percent} %",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    TextButton(onClick = onOpenBudgets) { Text("Ver presupuestos") }
                }
            }
        }
    }
}

@Composable
private fun LabelRow(label: String, money: Money, tone: MoneyTone = MoneyTone.Neutral) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        MoneyText(money, tone = tone)
    }
}

/** Gastos del mes por categoría como barras horizontales proporcionales al mayor gasto. */
@Composable
private fun SpendingCard(state: DashboardUiState) {
    FinanceCard(title = "Gastos del mes por categoría") {
        if (state.spending.isEmpty()) {
            Text("Aún no hay gastos este mes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@FinanceCard
        }
        val max = state.spending.maxOf { it.amount.centavos }.toFloat()
        state.spending.take(8).forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.categoryName, style = MaterialTheme.typography.bodyMedium)
                    MoneyText(item.amount)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(item.amount.centavos / max)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

