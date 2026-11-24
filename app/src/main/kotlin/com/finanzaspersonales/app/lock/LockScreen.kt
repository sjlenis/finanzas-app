package com.finanzaspersonales.app.lock

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.finanzaspersonales.core.security.AppLock
import com.finanzaspersonales.core.security.UnlockResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun LockScreen(appLock: AppLock) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var biometricEnabled by remember { mutableStateOf(false) }
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometric() {
        val act = activity ?: return
        val prompt = BiometricPrompt(
            act,
            ContextCompat.getMainExecutor(act),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLock.unlockWithBiometric()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear Finanzas")
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .setNegativeButtonText("Usar PIN")
                .build(),
        )
    }

    LaunchedEffect(Unit) {
        biometricEnabled = appLock.biometricEnabled().first()
        if (biometricEnabled && biometricAvailable) showBiometric()
    }

    fun submit() {
        scope.launch {
            when (val r = appLock.unlockWithPin(pin, SystemClock.elapsedRealtime())) {
                UnlockResult.Success -> Unit
                UnlockResult.WrongPin -> { error = "PIN incorrecto"; pin = "" }
                is UnlockResult.LockedOut -> { error = "Demasiados intentos. Espera ${r.remainingSeconds} segundos."; pin = "" }
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Ingresa tu PIN", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = pin,
                onValueChange = { v ->
                    pin = v.filter(Char::isDigit).take(6)
                    error = null
                },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            Button(onClick = ::submit, enabled = pin.length >= 4) { Text("Desbloquear") }
            if (biometricEnabled && biometricAvailable) {
                OutlinedButton(onClick = ::showBiometric) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                    Text("  Usar huella o rostro")
                }
            }
        }
    }
}
