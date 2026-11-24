package com.finanzaspersonales.core.model

/**
 * Interpreta lo que una persona escribe como valor en pesos.
 *
 * Acepta "15000", "15.000", "$15.000", "15.000,50" y "1.250.000".
 * El punto es separador de miles y la coma separa centavos (uso colombiano).
 * Devuelve null si el texto no es un valor válido.
 */
object MoneyInput {

    private const val MAX_PESOS = 999_999_999_999L // casi un billón: límite sensato de entrada

    fun parse(text: String): Money? {
        val cleaned = text.trim().removePrefix("$").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(',')
        if (parts.size > 2) return null
        val integerPart = parts[0]
        if (integerPart.isEmpty() || !integerPart.all { it.isDigit() || it == '.' }) return null
        // Si hay puntos, deben agrupar de a tres dígitos: "15.000" sí, "15.00" no.
        if (integerPart.contains('.')) {
            val groups = integerPart.split('.')
            if (groups.first().isEmpty() || groups.first().length > 3) return null
            if (groups.drop(1).any { it.length != 3 }) return null
        }
        val digits = integerPart.replace(".", "")
        if (digits.length > MAX_PESOS.toString().length) return null
        val pesos = digits.toLongOrNull() ?: return null
        if (pesos > MAX_PESOS) return null
        val cents = if (parts.size == 2) {
            val c = parts[1]
            if (c.isEmpty() || c.length > 2 || !c.all { it.isDigit() }) return null
            c.padEnd(2, '0').toLong()
        } else {
            0L
        }
        return Money(pesos * Money.CENTAVOS_POR_PESO + cents)
    }

    /** Texto para un campo de edición, sin símbolo: 1800000 centavos -> "18.000". */
    fun toEditableText(money: Money): String = money.abs().formatCop(withSymbol = false)
}
