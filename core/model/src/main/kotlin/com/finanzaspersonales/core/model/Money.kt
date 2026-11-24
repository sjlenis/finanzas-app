package com.finanzaspersonales.core.model

/**
 * Cantidad de dinero en pesos colombianos, guardada como centavos en un [Long].
 *
 * Nunca se usan Float ni Double para dinero: así se evitan los errores de redondeo
 * de punto flotante. 18.000 pesos se representan como `Money(1_800_000)`.
 */
@JvmInline
value class Money(val centavos: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(centavos, other.centavos))

    operator fun minus(other: Money): Money = Money(Math.subtractExact(centavos, other.centavos))

    operator fun unaryMinus(): Money = Money(Math.negateExact(centavos))

    operator fun times(factor: Int): Money = Money(Math.multiplyExact(centavos, factor.toLong()))

    override fun compareTo(other: Money): Int = centavos.compareTo(other.centavos)

    val isNegative: Boolean get() = centavos < 0
    val isZero: Boolean get() = centavos == 0L

    fun abs(): Money = if (centavos < 0) -this else this

    /** Pesos completos, truncando centavos. */
    val pesos: Long get() = centavos / CENTAVOS_POR_PESO

    companion object {
        const val CENTAVOS_POR_PESO = 100L
        val ZERO = Money(0)

        fun ofPesos(pesos: Long): Money = Money(Math.multiplyExact(pesos, CENTAVOS_POR_PESO))
    }
}

fun Iterable<Money>.sum(): Money = fold(Money.ZERO) { acc, m -> acc + m }
