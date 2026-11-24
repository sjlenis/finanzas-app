package com.finanzaspersonales.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.finanzaspersonales.app.lock.LockScreen
import com.finanzaspersonales.app.navigation.FinanzasApp
import com.finanzaspersonales.core.designsystem.theme.FinanzasTheme
import com.finanzaspersonales.core.security.AppLock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FragmentActivity (y no ComponentActivity) porque BiometricPrompt la necesita.
 * La app se muestra detrás de [LockScreen] hasta que se desbloquea.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appLock: AppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { appLock.onAppStart() }
        setContent {
            FinanzasTheme {
                val locked by appLock.locked.collectAsStateWithLifecycle()
                when (locked) {
                    null -> Surface(Modifier.fillMaxSize()) {} // leyendo la configuración (milisegundos)
                    true -> LockScreen(appLock = appLock)
                    false -> FinanzasApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { appLock.onForeground(SystemClock.elapsedRealtime()) }
    }

    override fun onStop() {
        super.onStop()
        // No contar como "salir" un cambio de configuración (por ejemplo, rotar la pantalla).
        if (!isChangingConfigurations) appLock.onBackground(SystemClock.elapsedRealtime())
    }
}
