package com.finanzaspersonales.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    onNotifications: () -> Unit = {},
    onPending: () -> Unit = {},
    onDebts: () -> Unit = {},
    onGoals: () -> Unit = {},
    onEmergencyFund: () -> Unit = {},
    onCalendar: () -> Unit = {},
    onInsights: () -> Unit = {},
    onStatistics: () -> Unit = {},
) {
    val sections = listOf(
        Section("Cuentas", Icons.Outlined.AccountBalance, "Saldos, conciliación e historial por cuenta", onAccounts),
        Section("Categorías", Icons.Outlined.Category, "Crear categorías y subcategorías propias", onCategories),
        Section("Notificaciones bancarias", Icons.Outlined.NotificationsActive, "Detectar movimientos de Nequi y Bancolombia", onNotifications),
        Section("Por revisar", Icons.Outlined.DoneAll, "Movimientos detectados pendientes de confirmar", onPending),
        Section("Seguridad", Icons.Outlined.Lock, "PIN, huella y bloqueo automático", onSecurity),
        Section("Deudas", Icons.Outlined.CreditCard, "Plan bola de nieve o avalancha, pagos e intereses", onDebts),
        Section("Metas de ahorro", Icons.Outlined.Savings, "Cuánto ahorrar y si te alcanza", onGoals),
        Section("Fondo de emergencia", Icons.Outlined.Shield, "De 3 a 6 meses de gastos esenciales", onEmergencyFund),
        Section("Calendario de pagos", Icons.Outlined.CalendarMonth, "Pagos fijos, cuotas e ingresos del mes", onCalendar),
        Section("Análisis y consejos", Icons.Outlined.Lightbulb, "Recomendaciones, gastos hormiga y ¿puedo comprarlo?", onInsights),
        Section("Estadísticas", Icons.Outlined.BarChart, "Gráficos, flujo de caja y patrimonio neto", onStatistics),
    )
    val context = LocalContext.current
    // Versión instalada, para confirmar que se actualizó al APK correcto.
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
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
        item {
            Text(
                "Versión $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}
