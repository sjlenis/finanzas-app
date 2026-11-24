package com.finanzaspersonales.feature.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.data.NotificationImporter
import com.finanzaspersonales.core.data.NotificationSample
import com.finanzaspersonales.core.data.UserPreferences
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Clase del servicio que lee las notificaciones (vive en el módulo :app). */
private const val LISTENER_CLASS = "com.finanzaspersonales.app.notifications.BankNotificationListener"

data class NotificationSettingsState(
    val detectionEnabled: Boolean = false,
    val samplesEnabled: Boolean = false,
    val samples: List<NotificationSample> = emptyList(),
)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val preferences: UserPreferences,
    private val importer: NotificationImporter,
) : ViewModel() {

    val state: StateFlow<NotificationSettingsState> = combine(
        preferences.notificationDetectionEnabled,
        preferences.notificationSamplesEnabled,
        importer.observeSamples(),
    ) { detection, samples, list -> NotificationSettingsState(detection, samples, list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsState())

    init {
        viewModelScope.launch { importer.purgeOldSamples() }
    }

    fun setDetection(enabled: Boolean) = viewModelScope.launch { preferences.setNotificationDetection(enabled) }

    fun setSamples(enabled: Boolean) = viewModelScope.launch {
        preferences.setNotificationSamples(enabled)
        if (!enabled) importer.clearSamples()
    }

    fun clearSamples() = viewModelScope.launch { importer.clearSamples() }
}

private fun hasListenerAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun openListenerSettings(context: Context) {
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context.packageName, LISTENER_CLASS).flattenToString(),
        )
    } else {
        null
    }
    val intents = listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    for (intent in intents) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun copySamples(context: Context, samples: List<NotificationSample>) {
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val text = samples.joinToString("\n\n") {
        "[${it.bank} · ${it.postedAt.atZone(BogotaTime.ZONE).format(format)}] ${it.maskedText}\n→ ${it.result}"
    }
    val clip = ClipData.newPlainText("Muestras de notificaciones", text)
    // Marca el contenido como sensible para que Android no lo muestre en la vista previa.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsRoute(onBack: () -> Unit, viewModel: NotificationSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var access by remember { mutableStateOf(hasListenerAccess(context)) }
    var confirmClear by remember { mutableStateOf(false) }
    // Al volver de los ajustes de Android se revisa otra vez el permiso.
    LifecycleResumeEffect(Unit) {
        access = hasListenerAccess(context)
        onPauseOrDispose { }
    }
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMM, h:mm a", java.util.Locale.forLanguageTag("es-CO")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notificaciones bancarias") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FinanceCard(title = "Cómo funciona") {
                    Text(
                        "La app lee solo las notificaciones de Nequi y Bancolombia (sus apps y sus SMS). " +
                            "De cada una toma el valor, el tipo y el comercio o la persona, y crea un movimiento " +
                            "por revisar: nada cuenta en tus saldos hasta que lo confirmes.\n\n" +
                            "No guarda el texto de la notificación, ignora códigos y claves, " +
                            "no usa internet y nunca entra a tus apps bancarias.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                FinanceCard(title = "1. Permiso de Android") {
                    Text(
                        if (access) "Permiso concedido." else "Falta permitir el acceso a notificaciones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (access) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { openListenerSettings(context) }) {
                        Text(if (access) "Ver permiso" else "Dar permiso")
                    }
                    if (!access) {
                        Text(
                            "Si Android dice \"Ajuste restringido\" (pasa con apps instaladas desde un APK): abre la " +
                                "información de la app, toca los tres puntos ⋮ arriba a la derecha, elige " +
                                "\"Permitir ajustes restringidos\" y vuelve a intentarlo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { openAppInfo(context) }) { Text("Abrir información de la app") }
                    }
                }
            }
            item {
                FinanceCard(title = "2. Detección") {
                    SwitchRow(
                        title = "Detectar movimientos",
                        subtitle = if (state.detectionEnabled && !access) "Activada, pero falta el permiso de Android" else null,
                        checked = state.detectionEnabled,
                        onChange = viewModel::setDetection,
                    )
                    Text(
                        "Para detectar movimientos, tus cuentas deben llamarse o tener como entidad \"Nequi\" y \"Bancolombia\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                FinanceCard(title = "Modo de muestras (opcional)") {
                    SwitchRow(
                        title = "Guardar muestras para mejorar la lectura",
                        subtitle = null,
                        checked = state.samplesEnabled,
                        onChange = viewModel::setSamples,
                    )
                    Text(
                        "Guarda en tu teléfono, cifrado, el texto de las notificaciones de Nequi y Bancolombia con los " +
                            "números de cuenta, tarjeta y teléfono tapados. Se borran solas a los 7 días o al apagar esta opción. " +
                            "Los nombres de personas no se tapan: revísalas antes de compartirlas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.samples.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { copySamples(context, state.samples) }) { Text("Copiar todas") }
                            TextButton(onClick = { confirmClear = true }) { Text("Borrar todas") }
                        }
                    }
                }
            }
            items(state.samples, key = { it.id }) { sample ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${sample.bank} · ${sample.postedAt.atZone(BogotaTime.ZONE).format(dateFormat)} · ${sample.result}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(sample.maskedText, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar las muestras?") },
            text = { Text("Se borran todas las muestras guardadas. Los movimientos no se tocan.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearSamples() }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
