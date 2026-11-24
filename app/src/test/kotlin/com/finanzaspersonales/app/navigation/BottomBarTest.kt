package com.finanzaspersonales.app.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.finanzaspersonales.core.designsystem.theme.FinanzasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BottomBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun muestraLosCincoApartadosYCambiaDeSeleccion() {
        compose.setContent {
            FinanzasTheme {
                var current by remember { mutableStateOf(TopLevelDestination.HOME) }
                FinanzasBottomBar(current = current) { current = it }
            }
        }
        listOf("Inicio", "Movimientos", "Asistente", "Presupuestos", "Perfil").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithTag("tab_inicio").assertIsSelected()
        compose.onNodeWithTag("tab_asistente").performClick()
        compose.onNodeWithTag("tab_asistente").assertIsSelected()
    }
}
