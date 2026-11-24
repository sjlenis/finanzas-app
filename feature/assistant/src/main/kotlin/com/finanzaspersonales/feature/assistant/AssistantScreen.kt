package com.finanzaspersonales.feature.assistant

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import com.finanzaspersonales.core.designsystem.component.EmptyState

@Composable
fun AssistantRoute() {
    EmptyState(
        icon = Icons.Outlined.Forum,
        title = "Asistente financiero",
        message = "Podrás escribir cosas como \"Hoy gasté 15.000 en almuerzo\" y confirmar el registro " +
            "en una tarjeta. El chat llega en la Fase 4.",
    )
}
