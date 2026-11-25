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

object Amortization {
    /**
     * Cuota fija (sistema francés) para pagar [balance] en [installments] cuotas con la
     * tasa mensual dada. Sin interés, es el saldo dividido en las cuotas. Redondea al peso hacia arriba.
     */
    fun installment(balance: Money, monthlyRate: BigDecimal, installments: Int): Money {
        require(installments > 0) { "Debe quedar al menos una cuota" }
        if (balance.centavos <= 0) return Money.ZERO
        val mc = MathContext(20, RoundingMode.HALF_EVEN)
        val b = BigDecimal(balance.centavos)
        val raw = if (monthlyRate.signum() == 0) {
            b.divide(BigDecimal(installments), mc)
        } else {
            val factor = BigDecimal.ONE.add(monthlyRate).pow(installments, mc)
            b.multiply(monthlyRate, mc).multiply(factor, mc).divide(factor.subtract(BigDecimal.ONE), mc)
        }
        val pesos = raw.divide(BigDecimal(100), mc).setScale(0, RoundingMode.CEILING).longValueExact()
        return Money.ofPesos(pesos)
    }
}

/** Capital que se paga con [installments] cuotas de [installment] a la tasa mensual dada (valor presente). */
fun Amortization.principal(installment: Money, monthlyRate: BigDecimal, installments: Int): Money {
    if (installments <= 0 || installment.centavos <= 0) return Money.ZERO
    val mc = MathContext(20, RoundingMode.HALF_EVEN)
    val p = BigDecimal(installment.centavos)
    val raw = if (monthlyRate.signum() == 0) p.multiply(BigDecimal(installments)) else {
        val factor = BigDecimal.ONE.add(monthlyRate).pow(installments, mc)
        p.multiply(factor.subtract(BigDecimal.ONE), mc).divide(monthlyRate.multiply(factor, mc), mc)
    }
    return Money.ofPesos(raw.divide(BigDecimal(100), mc).setScale(0, RoundingMode.HALF_UP).longValueExact())
}
