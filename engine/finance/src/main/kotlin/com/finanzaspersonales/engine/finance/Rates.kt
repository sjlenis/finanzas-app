package com.finanzaspersonales.engine.finance

import com.finanzaspersonales.core.model.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Cómo expresa la entidad la tasa. En Colombia lo usual es efectiva anual (E.A.)
 * o mes vencido (M.V.). Las tasas se guardan en centésimas de punto: 25,5 % = 2550.
 */
enum class RateType(val label: String) {
    EFFECTIVE_ANNUAL("Efectiva anual (E.A.)"),
    MONTHLY("Mensual (M.V.)"),
    NONE("Sin interés"),
}

object Rates {
    private val MC = MathContext(20, RoundingMode.HALF_EVEN)

    /** Tasa mensual como fracción (0,02 = 2 % mensual). */
    fun monthlyRate(rateBasisPoints: Int, type: RateType): BigDecimal {
        if (type == RateType.NONE || rateBasisPoints <= 0) return BigDecimal.ZERO
        val rate = BigDecimal(rateBasisPoints).divide(BigDecimal(10_000), MC)
        return when (type) {
            RateType.MONTHLY -> rate
            // (1 + EA)^(1/12) - 1. Solo la tasa usa coma flotante; el dinero nunca.
            RateType.EFFECTIVE_ANNUAL -> BigDecimal(Math.pow(1 + rate.toDouble(), 1.0 / 12) - 1, MC)
            RateType.NONE -> BigDecimal.ZERO
        }
    }

    /** Interés de un mes sobre un saldo, redondeado al centavo. */
    fun interestFor(balance: Money, monthlyRate: BigDecimal): Money =
        Money(BigDecimal(balance.centavos).multiply(monthlyRate, MC).setScale(0, RoundingMode.HALF_UP).longValueExact())
}
