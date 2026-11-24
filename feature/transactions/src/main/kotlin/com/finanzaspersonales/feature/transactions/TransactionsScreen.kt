package com.finanzaspersonales.feature.transactions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.runtime.Composable
import com.finanzaspersonales.core.designsystem.component.EmptyState

@Composable
fun TransactionsRoute() {
    EmptyState(
        icon = Icons.AutoMirrored.Outlined.ReceiptLong,
        title = "Movimientos",
        message = "Aquí verás y filtrarás tus ingresos, gastos, transferencias y préstamos. " +
            "El registro y el historial llegan en la Fase 3.",
    )
}
