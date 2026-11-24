package com.finanzaspersonales.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanzasApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route
    val current = TopLevelDestination.entries.firstOrNull { it.route == currentRoute } ?: TopLevelDestination.HOME

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(current.label) }) },
        bottomBar = {
            FinanzasBottomBar(current = current) { destination ->
                navController.navigate(destination.route) {
                    // Navegación estándar de pestañas: no apila pantallas y conserva su estado.
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevelDestination.HOME.route) { DashboardRoute() }
            composable(TopLevelDestination.TRANSACTIONS.route) { TransactionsRoute() }
            composable(TopLevelDestination.ASSISTANT.route) { AssistantRoute() }
            composable(TopLevelDestination.BUDGETS.route) { BudgetsRoute() }
            composable(TopLevelDestination.PROFILE.route) { ProfileRoute() }
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
                label = { Text(destination.label) },
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
