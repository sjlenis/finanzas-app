package com.finanzaspersonales.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.finanzaspersonales.feature.assistant.AssistantRoute
import com.finanzaspersonales.feature.budgets.BudgetsRoute
import com.finanzaspersonales.feature.dashboard.DashboardRoute
import com.finanzaspersonales.feature.profile.ProfileRoute
import com.finanzaspersonales.feature.transactions.TransactionsRoute
import com.finanzaspersonales.feature.transactions.EntryEditorRoute
import com.finanzaspersonales.feature.transactions.CategoriesRoute
import com.finanzaspersonales.feature.accounts.AccountsRoute
import com.finanzaspersonales.feature.accounts.AccountDetailRoute
import com.finanzaspersonales.feature.settings.SecuritySettingsRoute
import com.finanzaspersonales.feature.settings.NotificationSettingsRoute
import com.finanzaspersonales.feature.transactions.PendingRoute
import com.finanzaspersonales.feature.planning.CalendarRoute
import com.finanzaspersonales.feature.planning.DebtDetailRoute
import com.finanzaspersonales.feature.planning.DebtDetailViewModel
import com.finanzaspersonales.feature.planning.DebtPlanRoute
import com.finanzaspersonales.feature.planning.DebtsRoute
import com.finanzaspersonales.feature.planning.EmergencyFundRoute
import com.finanzaspersonales.feature.planning.GoalsRoute
import com.finanzaspersonales.feature.planning.InsightsRoute
import com.finanzaspersonales.feature.planning.ScanRoute
import com.finanzaspersonales.feature.planning.StatisticsRoute
import androidx.navigation.NavType
import androidx.navigation.navArgument

/** Rutas secundarias (pantallas completas, sin barra inferior). */
object Routes {
    const val ENTRY = "movimiento?id={id}"
    fun entry(id: Long? = null) = if (id == null) "movimiento" else "movimiento?id=$id"
    const val CATEGORIES = "categorias"
    const val ACCOUNTS = "cuentas"
    const val ACCOUNT = "cuenta/{accountId}"
    fun account(id: Long) = "cuenta/$id"
    const val SECURITY = "seguridad"
    const val NOTIFICATIONS = "notificaciones"
    const val PENDING = "por-revisar"
    const val DEBTS = "deudas"
    const val DEBT = "deuda/{debtId}"
    fun debt(id: Long) = "deuda/$id"
    const val DEBT_PLAN = "plan-deudas"
    const val GOALS = "metas"
    const val EMERGENCY = "fondo-emergencia"
    const val CALENDAR = "calendario"
    const val INSIGHTS = "analisis"
    const val STATISTICS = "estadisticas"
    const val SCAN = "leer-foto"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanzasApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route
    val current = TopLevelDestination.entries.firstOrNull { it.route == currentRoute }

    Scaffold(
        // Las pantallas secundarias tienen su propia barra superior y no muestran la inferior.
        topBar = { if (current != null) CenterAlignedTopAppBar(title = { Text(current.label) }) },
        bottomBar = {
            if (current != null) {
                FinanzasBottomBar(current = current) { destination -> navController.navigateToTab(destination) }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            // Las pantallas secundarias manejan sus propios márgenes con su Scaffold.
            modifier = if (current != null) Modifier.padding(padding) else Modifier,
        ) {
            val back: () -> Unit = { navController.popBackStack() }
            val openEntry: (Long) -> Unit = { navController.navigate(Routes.entry(it)) }
            composable(TopLevelDestination.HOME.route) {
                DashboardRoute(
                    onOpenAccount = { navController.navigate(Routes.account(it)) },
                    onOpenSecurity = { navController.navigate(Routes.SECURITY) },
                    onOpenPending = { navController.navigate(Routes.PENDING) },
                    onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                    onOpenBudgets = { navController.navigateToTab(TopLevelDestination.BUDGETS) },
                    onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
                    onOpenStatistics = { navController.navigate(Routes.STATISTICS) },
                )
            }
            composable(TopLevelDestination.TRANSACTIONS.route) {
                TransactionsRoute(onAdd = { navController.navigate(Routes.entry()) }, onOpen = openEntry)
            }
            composable(TopLevelDestination.ASSISTANT.route) { AssistantRoute(onScanPhoto = { navController.navigate(Routes.SCAN) }) }
            composable(TopLevelDestination.BUDGETS.route) { BudgetsRoute(onCalendar = { navController.navigate(Routes.CALENDAR) }) }
            composable(TopLevelDestination.PROFILE.route) {
                ProfileRoute(
                    onAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onCategories = { navController.navigate(Routes.CATEGORIES) },
                    onSecurity = { navController.navigate(Routes.SECURITY) },
                    onNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                    onPending = { navController.navigate(Routes.PENDING) },
                    onDebts = { navController.navigate(Routes.DEBTS) },
                    onGoals = { navController.navigate(Routes.GOALS) },
                    onEmergencyFund = { navController.navigate(Routes.EMERGENCY) },
                    onCalendar = { navController.navigate(Routes.CALENDAR) },
                    onInsights = { navController.navigate(Routes.INSIGHTS) },
                    onStatistics = { navController.navigate(Routes.STATISTICS) },
                )
            }
            composable(
                Routes.ENTRY,
                arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
            ) { EntryEditorRoute(onDone = back) }
            composable(Routes.CATEGORIES) { CategoriesRoute(onBack = back) }
            composable(Routes.ACCOUNTS) {
                AccountsRoute(onBack = back, onOpen = { navController.navigate(Routes.account(it)) })
            }
            composable(Routes.ACCOUNT, arguments = listOf(navArgument("accountId") { type = NavType.LongType })) {
                AccountDetailRoute(onBack = back, onOpenTransaction = openEntry)
            }
            composable(Routes.SECURITY) { SecuritySettingsRoute(onBack = back) }
            composable(Routes.NOTIFICATIONS) { NotificationSettingsRoute(onBack = back) }
            composable(Routes.PENDING) { PendingRoute(onBack = back, onEdit = openEntry) }
            composable(Routes.DEBTS) {
                DebtsRoute(
                    onBack = back,
                    onOpen = { navController.navigate(Routes.debt(it)) },
                    onPlan = { navController.navigate(Routes.DEBT_PLAN) },
                    onScanPhoto = { navController.navigate(Routes.SCAN) },
                )
            }
            composable(Routes.DEBT, arguments = listOf(navArgument(DebtDetailViewModel.ARG_DEBT_ID) { type = NavType.LongType })) {
                DebtDetailRoute(onBack = back)
            }
            composable(Routes.DEBT_PLAN) { DebtPlanRoute(onBack = back) }
            composable(Routes.GOALS) { GoalsRoute(onBack = back) }
            composable(Routes.EMERGENCY) { EmergencyFundRoute(onBack = back) }
            composable(Routes.INSIGHTS) { InsightsRoute(onBack = back) }
            composable(Routes.STATISTICS) { StatisticsRoute(onBack = back) }
            composable(Routes.SCAN) { ScanRoute(onBack = back) }
            composable(Routes.CALENDAR) { CalendarRoute(onBack = back, onOpenDebt = { navController.navigate(Routes.debt(it)) }) }
        }
    }
}

@Composable
fun FinanzasBottomBar(
    current: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                label = {
                    // Una sola línea: si el texto no cabe (pantallas angostas o letra grande),
                    // se reduce el tamaño en vez de partir la palabra.
                    BasicText(
                        text = destination.label,
                        maxLines = 1,
                        softWrap = false,
                        style = LocalTextStyle.current.copy(color = LocalContentColor.current),
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = LocalTextStyle.current.fontSize),
                    )
                },
                modifier = Modifier.testTag("tab_${destination.route}"),
                icon = {
                    val icon = if (selected) destination.selectedIcon else destination.unselectedIcon
                    if (destination.highlighted) {
                        // Acceso destacado: el asistente va dentro de un círculo de color primario.
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        Icon(icon, contentDescription = null)
                    }
                },
            )
        }
    }
}

/** Navegación estándar de pestañas: no apila pantallas y conserva su estado. */
private fun androidx.navigation.NavHostController.navigateToTab(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
