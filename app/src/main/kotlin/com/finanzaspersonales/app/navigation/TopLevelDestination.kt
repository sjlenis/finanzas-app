package com.finanzaspersonales.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.ui.graphics.vector.ImageVector

/** Los cinco apartados de la barra inferior, en el orden acordado. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    /** El asistente se muestra destacado porque será la función más usada. */
    val highlighted: Boolean = false,
) {
    HOME("inicio", "Inicio", Icons.Filled.Home, Icons.Outlined.Home),
    TRANSACTIONS("movimientos", "Movimientos", Icons.AutoMirrored.Filled.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong),
    ASSISTANT("asistente", "Asistente", Icons.Filled.Forum, Icons.Outlined.Forum, highlighted = true),
    BUDGETS("presupuestos", "Presupuestos", Icons.Filled.PieChart, Icons.Outlined.PieChart),
    PROFILE("perfil", "Perfil", Icons.Filled.Person, Icons.Outlined.Person),
}
