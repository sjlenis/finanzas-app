package com.finanzaspersonales.engine.finance

import com.finanzaspersonales.core.model.Money
import java.time.LocalDate
import java.time.YearMonth

/** Series mensuales para las estadísticas, reconstruidas desde el historial guardado. */
object Statistics {

    /** Los últimos [count] meses terminando en el de [today], del más antiguo al actual. */
    fun lastMonths(today: LocalDate, count: Int): List<YearMonth> {
        val current = YearMonth.from(today)
        return (count - 1 downTo 0).map { current.minusMonths(it.toLong()) }
    }

    /**
     * Saldo acumulado al final de cada mes a partir de movimientos con signo (aportes
     * positivos, retiros negativos). Sirve para la evolución del ahorro.
     */
    fun cumulativeByMonth(events: List<Pair<LocalDate, Money>>, months: List<YearMonth>): List<Money> =
        months.map { m ->
            val end = m.atEndOfMonth()
            Money(events.filter { !it.first.isAfter(end) }.sumOf { it.second.centavos })
        }

    /** Historial de una deuda: cuándo se registró, su saldo de partida y cada pago. */
    data class DebtHistory(
        val createdOn: LocalDate,
        /** Saldo que tenía al registrarla (antes de cualquier pago registrado). */
        val startingBalance: Money,
        /** Fecha del pago y saldo después de él. */
        val payments: List<Pair<LocalDate, Money>>,
    )

    /**
     * Total adeudado al final de cada mes: para cada deuda, el saldo después del último
     * pago de ese mes o antes; si aún no había pagos, su saldo de partida. Una deuda
     * registrada después de ese mes no cuenta.
     */
    fun debtByMonth(debts: List<DebtHistory>, months: List<YearMonth>): List<Money> =
        months.map { m ->
            val end = m.atEndOfMonth()
            Money(
                debts.filter { !it.createdOn.isAfter(end) }.sumOf { d ->
                    (d.payments.filter { !it.first.isAfter(end) }.maxByOrNull { it.first }?.second ?: d.startingBalance).centavos
                },
            )
        }
}
