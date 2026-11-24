package com.finanzaspersonales.feature.settings

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.security.AppLock
import com.finanzaspersonales.core.security.PinHasher
import com.finanzaspersonales.core.security.SecurityConfig
import com.finanzaspersonales.core.security.SecuritySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val settings: SecuritySettings,
    private val appLock: AppLock,
) : ViewModel() {
    val config: StateFlow<SecurityConfig?> = settings.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPin(pin: String) = viewModelScope.launch {
        settings.setPin(pin)
        appLock.markUnlocked()
    }

    fun removePin() = viewModelScope.launch { settings.removePin() }
    fun setBiometric(enabled: Boolean) = viewModelScope.launch { settings.setBiometricEnabled(enabled) }
    fun setAutoLock(minutes: Int) = viewModelScope.launch { settings.setAutoLockMinutes(minutes) }
}

private val autoLockChoices = listOf(
    Choice(0, "Inmediatamente"),
    Choice(1, "Después de 1 minuto"),
    Choice(5, "Después de 5 minutos"),
    Choice(15, "Después de 15 minutos"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsRoute(onBack: () -> Unit, viewModel: SecuritySettingsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seguridad") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            )
        },
    ) { padding ->
        val c = config ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(if (c.hasPin) "Cambiar PIN" else "Crear PIN") },
                supportingContent = {
                    Text(if (c.hasPin) "La app pide el PIN al abrirse." else "Recomendado: protege tus datos financieros con un PIN de 4 a 6 dígitos.")
                },
                modifier = Modifier.padding(vertical = 4.dp),
                trailingContent = { TextButton(onClick = { settingPin = true }) { Text(if (c.hasPin) "Cambiar" else "Crear") } },
            )
            if (c.hasPin) {
                ListItem(
                    headlineContent = { Text("Desbloquear con huella o rostro") },
                    supportingContent = {
                        Text(if (biometricAvailable) "El PIN sigue disponible como alternativa." else "Este teléfono no tiene huella o rostro configurados.")
                    },
                    trailingContent = {
                        Switch(checked = c.biometricEnabled && biometricAvailable, enabled = biometricAvailable, onCheckedChange = viewModel::setBiometric)
                    },
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ChoiceField(
                        label = "Bloquear al salir de la app",
                        selected = c.autoLockMinutes,
                        choices = autoLockChoices,
                        onSelect = viewModel::setAutoLock,
                    )
                }
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Quitar PIN", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                "Tus datos están cifrados en el teléfono con una clave protegida por el hardware seguro (Android Keystore). " +
                    "El PIN no se guarda: solo se guarda una huella criptográfica que no permite recuperarlo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (settingPin) {
        NewPinDialog(onDismiss = { settingPin = false }, onConfirm = { viewModel.setPin(it); settingPin = false })
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("¿Quitar el PIN?") },
            text = { Text("Cualquier persona con tu teléfono desbloqueado podrá ver tu información financiera.") },
            confirmButton = { TextButton(onClick = { viewModel.removePin(); confirmRemove = false }) { Text("Quitar") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun NewPinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val valid = PinHasher.isValidPin(pin)
    val matches = pin == repeat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(pin, { pin = it }, "PIN (4 a 6 dígitos)")
                PinField(repeat, { repeat = it }, "Repite el PIN", isError = repeat.isNotEmpty() && !matches)
                if (repeat.isNotEmpty() && !matches) Text("Los PIN no coinciden", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pin) }, enabled = valid && matches) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
