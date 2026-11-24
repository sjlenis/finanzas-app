package com.finanzaspersonales.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private data class Section(
    val title: String,
    val icon: ImageVector,
    val subtitle: String,
    val onClick: (() -> Unit)? = null,
)

/** Menú secundario. Las secciones aún no construidas indican en qué fase llegan. */
@Composable
fun ProfileRoute(
    onAccounts: () -> Unit,
    onCategories: () -> Unit,
    onSecurity: () -> Unit,
) {
    val sections = listOf(
        Section("Cuentas", Icons.Outlined.AccountBalance, "Saldos, conciliación e historial por cuenta", onAccounts),
        Section("Categorías", Icons.Outlined.Category, "Crear categorías y subcategorías propias", onCategories),
        Section("Seguridad", Icons.Outlined.Lock, "PIN, huella y bloqueo automático", onSecurity),
        Section("Deudas", Icons.Outlined.CreditCard, "Llega en la Fase 6"),
        Section("Ahorros", Icons.Outlined.Savings, "Llega en la Fase 6"),
        Section("Fondo de emergencia", Icons.Outlined.Shield, "Llega en la Fase 6"),
        Section("Calendario", Icons.Outlined.CalendarMonth, "Llega en la Fase 6"),
        Section("Estadísticas", Icons.Outlined.BarChart, "Llega en la Fase 7"),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        items(sections) { s ->
            ListItem(
                modifier = if (s.onClick != null) Modifier.clickable(onClick = s.onClick) else Modifier,
                headlineContent = { Text(s.title) },
                supportingContent = { Text(s.subtitle) },
                leadingContent = { Icon(s.icon, contentDescription = null) },
                trailingContent = if (s.onClick != null) ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }) else null,
            )
        }
    }
}
