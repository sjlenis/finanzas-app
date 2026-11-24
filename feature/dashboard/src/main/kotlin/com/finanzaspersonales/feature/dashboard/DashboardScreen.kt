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

@Composable
fun DashboardRoute(
    onOpenAccount: (Long) -> Unit,
    onOpenSecurity: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(state, onOpenAccount, onOpenSecurity)
}

/**
 * Panel de inicio. Solo muestra datos reales de la base de datos. Las tarjetas de
 * secciones aún no construidas lo dicen claramente en vez de mostrar cifras inventadas.
 */
@Composable
fun DashboardScreen(state: DashboardUiState, onOpenAccount: (Long) -> Unit, onOpenSecurity: () -> Unit) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
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
                Text(
                    "El dinero realmente disponible (descontando reservas y compromisos) llegará en la Fase 6.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        items(upcomingSections) { (title, phase) ->
            FinanceCard(title = title) {
                Text("Disponible en la $phase.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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

private val upcomingSections = listOf(
    "Dinero comprometido y próximos pagos" to "Fase 6",
    "Deudas" to "Fase 6",
    "Ahorro y fondo de emergencia" to "Fase 6",
    "Estado de los presupuestos" to "Fase 6",
)
