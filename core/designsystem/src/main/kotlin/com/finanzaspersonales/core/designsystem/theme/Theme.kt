package com.finanzaspersonales.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Teal40, onPrimary = Color.White, primaryContainer = Teal90, onPrimaryContainer = Teal10,
    secondary = Slate40, onSecondary = Color.White, secondaryContainer = Slate90, onSecondaryContainer = Slate10,
    tertiary = Amber40, onTertiary = Color.White, tertiaryContainer = Amber90, onTertiaryContainer = Amber30,
    error = Red40, background = Neutral98, onBackground = Neutral10, surface = Neutral98, onSurface = Neutral10,
    surfaceVariant = NeutralVariant90, onSurfaceVariant = NeutralVariant30, outline = NeutralVariant50,
)

private val DarkColors = darkColorScheme(
    primary = Teal80, onPrimary = Teal20, primaryContainer = Teal30, onPrimaryContainer = Teal90,
    secondary = Slate80, onSecondary = Slate20, secondaryContainer = Slate30, onSecondaryContainer = Slate90,
    tertiary = Amber80, onTertiary = Amber30, tertiaryContainer = Amber30, onTertiaryContainer = Amber90,
    error = Red80, background = Neutral6, onBackground = Neutral90, surface = Neutral6, onSurface = Neutral90,
    surfaceVariant = NeutralVariant30, onSurfaceVariant = NeutralVariant80, outline = NeutralVariant50,
)

@Immutable
data class FinanceColors(val income: Color, val expense: Color)

val LocalFinanceColors = staticCompositionLocalOf {
    FinanceColors(income = MoneyColors.IncomeLight, expense = MoneyColors.ExpenseLight)
}

/**
 * Tema de la app. Por ahora sigue el modo claro/oscuro del sistema; la opción manual
 * llegará con la pantalla de configuración.
 */
@Composable
fun FinanzasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    val financeColors = if (darkTheme) {
        FinanceColors(MoneyColors.IncomeDark, MoneyColors.ExpenseDark)
    } else {
        FinanceColors(MoneyColors.IncomeLight, MoneyColors.ExpenseLight)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalFinanceColors provides financeColors) {
        MaterialTheme(colorScheme = colors, typography = FinanzasTypography, content = content)
    }
}
