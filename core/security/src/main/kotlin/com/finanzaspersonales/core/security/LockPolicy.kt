package com.finanzaspersonales.core.security

/**
 * Reglas de bloqueo, sin dependencias de Android para poder probarlas.
 *
 * - Se bloquea al abrir la app y al volver después de [autoLockMinutes] en segundo plano.
 * - Tras [MAX_ATTEMPTS] PIN incorrectos se espera [LOCKOUT_MS] antes de volver a intentar.
 */
class LockPolicy {
    private var backgroundSinceMs: Long? = null
    private var failedAttempts = 0
    private var lockedOutUntilMs = 0L

    fun onBackground(nowMs: Long) {
        backgroundSinceMs = nowMs
    }

    /** Devuelve true si al volver a primer plano hay que pedir el PIN. */
    fun shouldLockOnForeground(nowMs: Long, autoLockMinutes: Int): Boolean {
        val since = backgroundSinceMs ?: return false
        backgroundSinceMs = null
        return nowMs - since >= autoLockMinutes * 60_000L
    }

    fun remainingLockoutMs(nowMs: Long): Long = (lockedOutUntilMs - nowMs).coerceAtLeast(0)

    fun registerFailure(nowMs: Long) {
        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            lockedOutUntilMs = nowMs + LOCKOUT_MS
            failedAttempts = 0
        }
    }

    fun registerSuccess() {
        failedAttempts = 0
        lockedOutUntilMs = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}
