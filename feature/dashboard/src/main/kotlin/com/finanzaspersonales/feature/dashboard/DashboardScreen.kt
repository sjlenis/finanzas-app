package com.finanzaspersonales.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import com.finanzaspersonales.core.designsystem.theme.AmountLarge
import com.finanzaspersonales.core.model.Money

@Composable
fun DashboardRoute(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(state)
}

/**
 * Panel de inicio. Solo muestra datos reales de la base de datos. Las tarjetas de
 * secciones aún no construidas lo dicen claramente en vez de mostrar cifras inventadas.
 */
@Composable
fun DashboardScreen(state: DashboardUiState) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            FinanceCard(title = "Dinero total en cuentas") {
                MoneyText(state.total, style = AmountLarge)
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
            }
        }
        item {
            FinanceCard(title = "Saldo por cuenta") {
                state.accounts.forEachIndexed { index, account ->
                    if (index > 0) HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(account.name, style = MaterialTheme.typography.bodyLarge)
                        MoneyText(account.balance)
                    }
                }
                if (state.accounts.all { it.balance == Money.ZERO }) {
                    Text(
                        "Todas las cuentas están en $0. Podrás registrar tus saldos iniciales en la Fase 3.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(upcomingSections) { (title, phase) ->
            FinanceCard(title = title) {
                Text("Disponible en la $phase.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val upcomingSections = listOf(
    "Dinero comprometido y próximos pagos" to "Fase 6",
    "Deudas" to "Fase 6",
    "Ahorro y fondo de emergencia" to "Fase 6",
    "Estado de los presupuestos" to "Fase 6",
    "Gráficos" to "Fase 3",
)
