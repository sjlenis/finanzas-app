package com.finanzaspersonales.feature.budgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.runtime.Composable
import com.finanzaspersonales.core.designsystem.component.EmptyState

@Composable
fun BudgetsRoute() {
    EmptyState(
        icon = Icons.Outlined.PieChart,
        title = "Presupuestos",
        message = "Presupuestos mensuales por categoría, alertas y distribución recomendada de tus ingresos. " +
            "Llegan en la Fase 6.",
    )
}
