package com.finanzaspersonales.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UnlockResult {
    data object Success : UnlockResult
    data object WrongPin : UnlockResult
    data class LockedOut(val remainingSeconds: Long) : UnlockResult
}

/** Estado de bloqueo de toda la app. */
@Singleton
class AppLock @Inject constructor(
    private val settings: SecuritySettings,
) {
    private val policy = LockPolicy()
    /** null mientras se lee la configuración; true si hay que pedir el PIN. */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    /** Al arrancar el proceso: bloqueada solo si hay PIN configurado. */
    suspend fun onAppStart() {
        if (_locked.value == null) _locked.value = settings.current().hasPin
    }

    fun onBackground(nowMs: Long) = policy.onBackground(nowMs)

    suspend fun onForeground(nowMs: Long) {
        val config = settings.current()
        if (config.hasPin && policy.shouldLockOnForeground(nowMs, config.autoLockMinutes)) {
            _locked.value = true
        }
    }

    suspend fun unlockWithPin(pin: String, nowMs: Long): UnlockResult {
        val remaining = policy.remainingLockoutMs(nowMs)
        if (remaining > 0) return UnlockResult.LockedOut((remaining + 999) / 1000)
        val config = settings.current()
        val ok = config.hasPin && PinHasher.matches(pin, config.pinSalt!!, config.pinHash!!)
        return if (ok) {
            policy.registerSuccess()
            _locked.value = false
            UnlockResult.Success
        } else {
            policy.registerFailure(nowMs)
            val after = policy.remainingLockoutMs(nowMs)
            if (after > 0) UnlockResult.LockedOut((after + 999) / 1000) else UnlockResult.WrongPin
        }
    }

    fun biometricEnabled(): kotlinx.coroutines.flow.Flow<Boolean> =
        kotlinx.coroutines.flow.flow { emit(settings.current().let { it.hasPin && it.biometricEnabled }) }

    /** Llamado cuando la huella o el rostro se validaron con BiometricPrompt. */
    fun unlockWithBiometric() {
        policy.registerSuccess()
        _locked.value = false
    }

    /** Tras crear o quitar el PIN desde ajustes, la sesión actual sigue abierta. */
    fun markUnlocked() {
        _locked.value = false
    }
}
