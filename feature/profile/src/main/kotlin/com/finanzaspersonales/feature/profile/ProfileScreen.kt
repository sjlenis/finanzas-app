package com.finanzaspersonales.feature.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private data class Section(val title: String, val phase: String, val icon: ImageVector)

private val sections = listOf(
    Section("Cuentas", "Fase 3", Icons.Outlined.AccountBalance),
    Section("Deudas", "Fase 6", Icons.Outlined.CreditCard),
    Section("Ahorros", "Fase 6", Icons.Outlined.Savings),
    Section("Fondo de emergencia", "Fase 6", Icons.Outlined.Shield),
    Section("Calendario", "Fase 6", Icons.Outlined.CalendarMonth),
    Section("Estadísticas", "Fase 3", Icons.Outlined.BarChart),
    Section("Configuración", "Fase 3", Icons.Outlined.Settings),
)

/** Menú secundario. Cada sección indica en qué fase se construye. */
@Composable
fun ProfileRoute() {
    LazyColumn(Modifier.fillMaxSize()) {
        items(sections) { s ->
            ListItem(
                headlineContent = { Text(s.title) },
                supportingContent = { Text("Llega en la ${s.phase}") },
                leadingContent = { Icon(s.icon, contentDescription = null) },
            )
        }
    }
}
