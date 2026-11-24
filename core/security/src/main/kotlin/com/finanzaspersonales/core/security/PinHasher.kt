package com.finanzaspersonales.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * El PIN nunca se guarda: se guarda un hash PBKDF2-HMAC-SHA256 con sal aleatoria
 * y muchas iteraciones, para que no se pueda recuperar aunque alguien copie el archivo.
 */
object PinHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    val PIN_REGEX = Regex("^\\d{4,6}$")

    fun isValidPin(pin: String): Boolean = PIN_REGEX.matches(pin)

    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) })

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(bytes)
        } finally {
            spec.clearPassword()
        }
    }

    /** Comparación en tiempo constante. */
    fun matches(pin: String, salt: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expectedHash.toByteArray())
}
