package com.finanzaspersonales.core.model

/**
 * Formato colombiano: separador de miles con punto, centavos con coma y solo
 * si no son cero. Ejemplos: `$18.000`, `-$2.500,50`.
 *
 * Se implementa a mano para no depender del Locale del teléfono, que puede no ser es-CO.
 */
object MoneyFormat {

    fun format(money: Money, withSymbol: Boolean = true): String {
        val negative = money.centavos < 0
        // Valor absoluto sin signo: así también funciona con Long.MIN_VALUE.
        val abs: ULong = if (negative) (-money.centavos).toULong() else money.centavos.toULong()
        val pesos = abs / Money.CENTAVOS_POR_PESO.toULong()
        val cents = (abs % Money.CENTAVOS_POR_PESO.toULong()).toLong()
        val grouped = groupThousands(pesos.toString())
        val decimals = if (cents != 0L) "," + cents.toString().padStart(2, '0') else ""
        val sign = if (negative) "-" else ""
        val symbol = if (withSymbol) "$" else ""
        return "$sign$symbol$grouped$decimals"
    }

    private fun groupThousands(digits: String): String {
        val sb = StringBuilder()
        digits.forEachIndexed { index, c ->
            if (index > 0 && (digits.length - index) % 3 == 0) sb.append('.')
            sb.append(c)
        }
        return sb.toString()
    }
}

fun Money.formatCop(withSymbol: Boolean = true): String = MoneyFormat.format(this, withSymbol)
