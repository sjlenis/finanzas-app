package com.finanzaspersonales.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAndLockTest {

    @Test
    fun `hash verifica el PIN correcto y no guarda el PIN`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("4821", salt)
        assertFalse(hash.contains("4821"))
        assertTrue(PinHasher.matches("4821", salt, hash))
        assertFalse(PinHasher.matches("4822", salt, hash))
        assertNotEquals(hash, PinHasher.hash("4821", PinHasher.newSalt()))
    }

    @Test
    fun `formato de PIN`() {
        assertTrue(PinHasher.isValidPin("1234"))
        assertTrue(PinHasher.isValidPin("123456"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("1234567"))
        assertFalse(PinHasher.isValidPin("12a4"))
    }

    @Test
    fun `bloqueo automatico segun tiempo en segundo plano`() {
        val p = LockPolicy()
        assertFalse(p.shouldLockOnForeground(1_000, 1)) // nunca fue a segundo plano
        p.onBackground(0)
        assertFalse(p.shouldLockOnForeground(59_000, 1))
        p.onBackground(0)
        assertTrue(p.shouldLockOnForeground(60_000, 1))
        p.onBackground(0)
        assertTrue(p.shouldLockOnForeground(1, 0))
    }

    @Test
    fun `cinco intentos fallidos bloquean 30 segundos`() {
        val p = LockPolicy()
        repeat(4) { p.registerFailure(0) }
        assertEquals(0, p.remainingLockoutMs(0))
        p.registerFailure(0)
        assertEquals(LockPolicy.LOCKOUT_MS, p.remainingLockoutMs(0))
        assertEquals(0, p.remainingLockoutMs(LockPolicy.LOCKOUT_MS))
    }
}
