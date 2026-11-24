package com.finanzaspersonales.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Tipografía del sistema (sin descargar fuentes) con jerarquía clara para cifras.
private val Base = Typography()

val FinanzasTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
)

/** Estilo para montos grandes: números tabulares para que las cifras se alineen. */
val AmountLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
val AmountMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
