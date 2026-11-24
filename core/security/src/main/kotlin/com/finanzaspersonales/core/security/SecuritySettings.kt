package com.finanzaspersonales.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.securityStore: DataStore<Preferences> by preferencesDataStore(name = "security")

data class SecurityConfig(
    val pinHash: String?,
    val pinSalt: String?,
    val biometricEnabled: Boolean,
    /** Minutos en segundo plano antes de pedir PIN otra vez. 0 = siempre. */
    val autoLockMinutes: Int,
) {
    val hasPin: Boolean get() = pinHash != null && pinSalt != null
}

/** Ajustes de seguridad guardados en el teléfono (sin el PIN en claro). */
@Singleton
class SecuritySettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val BIOMETRIC = booleanPreferencesKey("biometric_enabled")
        val AUTO_LOCK = intPreferencesKey("auto_lock_minutes")
    }

    val config: Flow<SecurityConfig> = context.securityStore.data.map {
        SecurityConfig(
            pinHash = it[Keys.PIN_HASH],
            pinSalt = it[Keys.PIN_SALT],
            biometricEnabled = it[Keys.BIOMETRIC] ?: false,
            autoLockMinutes = it[Keys.AUTO_LOCK] ?: DEFAULT_AUTO_LOCK_MINUTES,
        )
    }

    suspend fun current(): SecurityConfig = config.first()

    suspend fun setPin(pin: String) {
        require(PinHasher.isValidPin(pin)) { "El PIN debe tener entre 4 y 6 dígitos" }
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash(pin, salt)
        context.securityStore.edit {
            it[Keys.PIN_SALT] = salt
            it[Keys.PIN_HASH] = hash
        }
    }

    suspend fun removePin() {
        context.securityStore.edit {
            it.remove(Keys.PIN_HASH)
            it.remove(Keys.PIN_SALT)
            it[Keys.BIOMETRIC] = false
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.securityStore.edit { it[Keys.BIOMETRIC] = enabled }
    }

    suspend fun setAutoLockMinutes(minutes: Int) {
        require(minutes >= 0)
        context.securityStore.edit { it[Keys.AUTO_LOCK] = minutes }
    }

    companion object {
        const val DEFAULT_AUTO_LOCK_MINUTES = 1
    }
}
