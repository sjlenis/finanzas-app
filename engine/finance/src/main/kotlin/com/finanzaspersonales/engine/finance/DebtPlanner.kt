package com.finanzaspersonales.engine.finance

import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.sum
import java.time.YearMonth

data class DebtInput(
    val id: Long,
    val name: String,
    val balance: Money,
    val rateBasisPoints: Int,
    val rateType: RateType,
    val minimumPayment: Money,
)

enum class DebtStrategy(val label: String, val explanation: String) {
    SNOWBALL("Bola de nieve", "Paga primero la deuda más pequeña: ves resultados rápido y ganas motivación."),
    AVALANCHE("Avalancha", "Paga primero la deuda con la tasa más alta: pagas menos intereses en total."),
}

data class DebtPayoff(val id: Long, val name: String, val month: YearMonth, val monthsFromStart: Int, val interestPaid: Money)

data class DebtPlan(
    val strategy: DebtStrategy?,
    /** Meses hasta quedar sin deudas; null si con estos pagos nunca se terminan. */
    val months: Int?,
    val debtFreeMonth: YearMonth?,
    val totalInterest: Money,
    val totalPaid: Money,
    /** Dinero que hay que destinar cada mes (cuotas mínimas + pago extra). */
    val monthlyBudget: Money,
    /** Orden en que se van terminando las deudas. */
    val payoffs: List<DebtPayoff>,
    val problems: List<String>,
) {
    val feasible: Boolean get() = months != null
}

data class DebtComparison(
    val snowball: DebtPlan,
    val avalanche: DebtPlan,
    /** Solo las cuotas mínimas, sin pago extra ni reinvertir cuotas liberadas. */
    val minimumOnly: DebtPlan,
) {
    /** La estrategia que paga menos intereses (en empate, la que termina antes). */
    val recommended: DebtPlan
        get() = listOf(avalanche, snowball).filter { it.feasible }
            .minWithOrNull(compareBy<DebtPlan> { it.totalInterest }.thenBy { it.months ?: Int.MAX_VALUE })
            ?: avalanche

    /** Intereses que te ahorras con el plan recomendado frente a pagar solo los mínimos. */
    val interestSaved: Money?
        get() = if (minimumOnly.feasible && recommended.feasible) minimumOnly.totalInterest - recommended.totalInterest else null
}

/**
 * Simula mes a mes el pago de las deudas. Cada mes: se suman los intereses, se paga
 * la cuota mínima de cada deuda y lo que sobra del presupuesto (pago extra + cuotas
 * de deudas ya terminadas) va a la deuda prioritaria según la estrategia.
 * Todo el dinero se calcula en centavos enteros.
 */
object DebtPlanner {

    const val MAX_MONTHS = 600

    fun compare(debts: List<DebtInput>, extraMonthly: Money, start: YearMonth): DebtComparison = DebtComparison(
        snowball = plan(debts, extraMonthly, DebtStrategy.SNOWBALL, start),
        avalanche = plan(debts, extraMonthly, DebtStrategy.AVALANCHE, start),
        minimumOnly = plan(debts, Money.ZERO, null, start),
    )

    /** [strategy] null = cada deuda con su cuota mínima, sin reinvertir lo liberado. */
    fun plan(debts: List<DebtInput>, extraMonthly: Money, strategy: DebtStrategy?, start: YearMonth): DebtPlan {
        require(!extraMonthly.isNegative) { "El pago extra no puede ser negativo" }
        val active = debts.filter { it.balance.centavos > 0 }
        val rates = active.associate { it.id to Rates.monthlyRate(it.rateBasisPoints, it.rateType) }
        val balances = active.associate { it.id to it.balance }.toMutableMap()
        val interestByDebt = active.associate { it.id to Money.ZERO }.toMutableMap()
        val monthlyBudget = active.map { it.minimumPayment }.sum() + extraMonthly
        val problems = mutableListOf<String>()
        active.filter { Rates.interestFor(it.balance, rates.getValue(it.id)) >= it.minimumPayment }.forEach {
            problems += "La cuota de ${it.name} no alcanza a cubrir los intereses del mes: el saldo no baja solo con esa cuota."
        }
        val payoffs = mutableListOf<DebtPayoff>()
        var totalInterest = Money.ZERO
        var totalPaid = Money.ZERO
        var month = 0
        val initialTotal = active.map { it.balance }.sum()
        while (balances.values.any { it.centavos > 0 }) {
            // Si el saldo crece sin control (cuotas menores que los intereses) se corta la simulación.
            val growing = balances.values.sum() > initialTotal * 10
            if (month >= MAX_MONTHS || growing) {
                return DebtPlan(strategy, null, null, totalInterest, totalPaid, monthlyBudget, payoffs, problems +
                    "Con estos pagos las deudas no se terminan en ${MAX_MONTHS / 12} años.")
            }
            month++
            val open = active.filter { balances.getValue(it.id).centavos > 0 }
            for (d in open) {
                val interest = Rates.interestFor(balances.getValue(d.id), rates.getValue(d.id))
                balances[d.id] = balances.getValue(d.id) + interest
                interestByDebt[d.id] = interestByDebt.getValue(d.id) + interest
                totalInterest += interest
            }
            var budget = if (strategy == null) open.map { it.minimumPayment }.sum() else monthlyBudget
            for (d in open) {
                val pay = minOf(d.minimumPayment, balances.getValue(d.id), budget)
                balances[d.id] = balances.getValue(d.id) - pay
                budget -= pay
                totalPaid += pay
            }
            if (strategy != null) {
                for (d in priority(open, balances, rates, strategy)) {
                    if (budget.centavos <= 0) break
                    val pay = minOf(budget, balances.getValue(d.id))
                    balances[d.id] = balances.getValue(d.id) - pay
                    budget -= pay
                    totalPaid += pay
                }
            }
            open.filter { balances.getValue(it.id).centavos <= 0 }.forEach {
                payoffs += DebtPayoff(it.id, it.name, start.plusMonths(month.toLong() - 1), month, interestByDebt.getValue(it.id))
            }
        }
        return DebtPlan(
            strategy = strategy,
            months = month,
            debtFreeMonth = if (month == 0) start else start.plusMonths(month.toLong() - 1),
            totalInterest = totalInterest,
            totalPaid = totalPaid,
            monthlyBudget = monthlyBudget,
            payoffs = payoffs,
            problems = problems,
        )
    }

    private fun priority(
        open: List<DebtInput>,
        balances: Map<Long, Money>,
        rates: Map<Long, java.math.BigDecimal>,
        strategy: DebtStrategy,
    ): List<DebtInput> = when (strategy) {
        DebtStrategy.SNOWBALL -> open.sortedWith(
            compareBy<DebtInput> { balances.getValue(it.id) }.thenByDescending { rates.getValue(it.id) },
        )
        DebtStrategy.AVALANCHE -> open.sortedWith(
            compareByDescending<DebtInput> { rates.getValue(it.id) }.thenBy { balances.getValue(it.id) },
        )
    }
}
