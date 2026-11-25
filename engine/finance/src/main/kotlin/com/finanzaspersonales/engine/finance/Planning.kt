package com.finanzaspersonales.engine.finance

import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.sum
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Redondeos para que las sugerencias sean cifras fáciles de recordar. */
internal object Round {
    private fun step(pesos: Long) = Money.ofPesos(pesos).centavos

    fun upTo(money: Money, pesos: Long): Money {
        if (money.centavos <= 0) return Money.ZERO
        val s = step(pesos)
        return Money(Math.multiplyExact((money.centavos + s - 1) / s, s))
    }

    fun downTo(money: Money, pesos: Long): Money {
        if (money.centavos <= 0) return Money.ZERO
        val s = step(pesos)
        return Money(money.centavos / s * s)
    }

    /** Porcentaje entero 0..100+ (puede pasar de 100 si se excede). */
    fun percent(part: Money, whole: Money): Int =
        if (whole.centavos <= 0) 0 else ((part.centavos.coerceAtLeast(0) * 100) / whole.centavos).toInt()

    fun ceilDiv(a: Money, months: Int): Money = Money((a.centavos + months - 1) / months)
}

// ------------------------------------------------------------------ fondo de emergencia

data class EmergencyPlan(
    val essentialMonthly: Money,
    val targetMonths: Int,
    val target: Money,
    val saved: Money,
    val percent: Int,
    /** Metas progresivas: 1 mes, 3 meses y la meta final. */
    val milestones: List<Pair<String, Money>>,
    val suggestedMonthly: Money,
    val suggestedWeekly: Money,
    /** Mes estimado para lograrlo con el aporte usado; null si el aporte es cero. */
    val eta: YearMonth?,
    val notes: List<String>,
)

object EmergencyFund {
    /** Parte del excedente mensual que se sugiere ahorrar: deja margen para imprevistos y metas. */
    const val SHARE_OF_SURPLUS_PERCENT = 30

    fun plan(
        essentialMonthly: Money,
        targetMonths: Int,
        saved: Money,
        monthlySurplus: Money,
        chosenMonthly: Money?,
        today: LocalDate,
        customTarget: Money? = null,
    ): EmergencyPlan {
        require(targetMonths in 1..12) { "Elige entre 1 y 12 meses" }
        val target = customTarget ?: (essentialMonthly * targetMonths)
        val remaining = (target - saved).let { if (it.isNegative) Money.ZERO else it }
        val notes = mutableListOf<String>()
        if (essentialMonthly.isZero) notes += "Aún no hay gastos esenciales registrados para calcular la meta. Registra tus gastos de un mes o fija la meta a mano."
        val suggested = when {
            remaining.isZero -> Money.ZERO
            monthlySurplus.centavos <= 0 -> {
                notes += "Este mes tus gastos y cuotas igualan o superan tus ingresos: primero conviene equilibrarlos. No se sugiere ahorrar con dinero que necesitas para tus obligaciones."
                Money.ZERO
            }
            else -> {
                val share = Money(monthlySurplus.centavos * SHARE_OF_SURPLUS_PERCENT / 100)
                minOf(remaining, Round.downTo(share, 1_000).let { if (it.isZero) Round.upTo(share, 100) else it })
            }
        }
        val used = chosenMonthly ?: suggested
        val eta = when {
            remaining.isZero -> YearMonth.from(today)
            used.centavos <= 0 -> null
            else -> YearMonth.from(today).plusMonths(((remaining.centavos + used.centavos - 1) / used.centavos))
        }
        val milestones = buildList {
            if (targetMonths > 1) add("1 mes de gastos" to essentialMonthly)
            if (targetMonths > 3) add("3 meses de gastos" to essentialMonthly * 3)
            add("Meta: $targetMonths ${if (targetMonths == 1) "mes" else "meses"}" to target)
        }
        return EmergencyPlan(
            essentialMonthly = essentialMonthly,
            targetMonths = targetMonths,
            target = target,
            saved = saved,
            percent = Round.percent(saved, target).coerceAtMost(100),
            milestones = milestones,
            suggestedMonthly = suggested,
            suggestedWeekly = Round.ceilDiv(suggested * 12, 52),
            eta = eta,
            notes = notes,
        )
    }
}

// ------------------------------------------------------------------ metas de ahorro

data class GoalPlan(
    val target: Money,
    val saved: Money,
    val remaining: Money,
    val percent: Int,
    val monthsLeft: Int?,
    val requiredMonthly: Money?,
    val requiredWeekly: Money?,
    /** true = cabe en tu excedente; false = no alcanza; null = sin fecha límite o sin datos. */
    val feasible: Boolean?,
    val message: String,
)

object SavingsGoals {
    fun plan(target: Money, saved: Money, deadline: LocalDate?, today: LocalDate, availableMonthly: Money): GoalPlan {
        val remaining = (target - saved).let { if (it.isNegative) Money.ZERO else it }
        val percent = Round.percent(saved, target).coerceAtMost(100)
        if (remaining.isZero) return GoalPlan(target, saved, remaining, 100, 0, Money.ZERO, Money.ZERO, true, "¡Meta cumplida!")
        if (deadline == null) {
            return GoalPlan(target, saved, remaining, percent, null, null, null, null, "Sin fecha límite: ponle una fecha para calcular cuánto ahorrar.")
        }
        if (!deadline.isAfter(today)) {
            return GoalPlan(target, saved, remaining, percent, 0, remaining, remaining, false, "La fecha límite ya pasó: cámbiala para recalcular.")
        }
        val days = ChronoUnit.DAYS.between(today, deadline)
        val months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(deadline)).toInt().coerceAtLeast(1)
        val weeks = ((days + 6) / 7).toInt().coerceAtLeast(1)
        val monthly = Round.ceilDiv(remaining, months)
        val weekly = Round.ceilDiv(remaining, weeks)
        val feasible = monthly <= availableMonthly
        val message = if (feasible) {
            "Ahorra ${monthly.formatShort()} al mes para lograrla a tiempo."
        } else if (availableMonthly.centavos <= 0) {
            "Hoy no tienes excedente mensual para esta meta sin afectar tus obligaciones."
        } else {
            "Necesitas ${monthly.formatShort()} al mes y tu excedente es ${availableMonthly.formatShort()}: considera ampliar la fecha."
        }
        return GoalPlan(target, saved, remaining, percent, months, monthly, weekly, feasible, message)
    }
}

internal fun Money.formatShort(): String = com.finanzaspersonales.core.model.MoneyFormat.format(this)

// ------------------------------------------------------------------ presupuestos

enum class BudgetLevel { OK, NEAR, OVER }

data class BudgetStatus(val limit: Money, val spent: Money) {
    val percent: Int get() = Round.percent(spent, limit)
    val remaining: Money get() = limit - spent
    val level: BudgetLevel
        get() = when {
            spent > limit -> BudgetLevel.OVER
            percent >= NEAR_PERCENT -> BudgetLevel.NEAR
            else -> BudgetLevel.OK
        }

    companion object {
        const val NEAR_PERCENT = 80
    }
}

data class CategoryHistory(val categoryId: Long, val name: String, val essential: Boolean, val monthlyAverage: Money)

data class BudgetSuggestion(val categoryId: Long, val name: String, val essential: Boolean, val suggested: Money, val average: Money)

data class BudgetRecommendation(
    val suggestions: List<BudgetSuggestion>,
    /** Ingreso esperado menos compromisos (cuotas de deudas y aportes de ahorro). */
    val availableForSpending: Money,
    val unallocated: Money,
    val notes: List<String>,
)

/**
 * Sugerencias de presupuesto basadas en tu propio gasto (no en 50/30/20): parte del
 * promedio de los últimos meses, protege los gastos esenciales y, si no alcanza,
 * recorta primero los no esenciales.
 */
object BudgetAdvisor {
    fun recommend(history: List<CategoryHistory>, expectedIncome: Money, commitments: Money): BudgetRecommendation {
        val available = expectedIncome - commitments
        val notes = mutableListOf<String>()
        val base = history.filter { it.monthlyAverage.centavos > 0 }
            .map { BudgetSuggestion(it.categoryId, it.name, it.essential, Round.upTo(it.monthlyAverage, 5_000), it.monthlyAverage) }
        if (base.isEmpty()) {
            return BudgetRecommendation(emptyList(), available, available, listOf("Registra gastos de al menos un mes para recibir sugerencias."))
        }
        if (expectedIncome.isZero) notes += "No hay ingresos registrados: las sugerencias solo reflejan tu gasto promedio."
        val essentials = base.filter { it.essential }.map { it.suggested }.sum()
        val others = base.filter { !it.essential }.map { it.suggested }.sum()
        val adjusted = if (expectedIncome.isZero || essentials + others <= available) {
            base
        } else {
            val room = available - essentials
            if (room.centavos <= 0) {
                notes += "Tus gastos esenciales ya ocupan todo tu ingreso disponible. Los no esenciales quedan en cero y conviene revisar deudas o ingresos."
            } else {
                notes += "Tu gasto promedio supera lo disponible: recorté los gastos no esenciales en la misma proporción."
            }
            base.map { s ->
                if (s.essential) s
                else if (room.centavos <= 0) s.copy(suggested = Money.ZERO)
                else s.copy(suggested = Round.downTo(Money(s.suggested.centavos * room.centavos / others.centavos), 1_000))
            }
        }
        val total = adjusted.map { it.suggested }.sum()
        return BudgetRecommendation(adjusted.sortedByDescending { it.suggested }, available, available - total, notes)
    }
}

// ------------------------------------------------------------------ dinero disponible

data class AvailableMoney(
    val total: Money,
    val obligations: Money,
    val emergencyFund: Money,
    val goals: Money,
) {
    /** Lo que puedes gastar libremente. Negativo = te falta dinero para cubrir lo reservado. */
    val available: Money get() = total - obligations - emergencyFund - goals
}

object Projection {
    /**
     * Saldo estimado a fin de mes: lo que tienes hoy, más ingresos previstos que faltan,
     * menos pagos pendientes, menos tu gasto diario promedio por los días que quedan.
     */
    fun endOfMonth(liquidNow: Money, expectedIncome: Money, pendingPayments: Money, averageDailySpending: Money, today: LocalDate): Money {
        val daysLeft = today.lengthOfMonth() - today.dayOfMonth
        return liquidNow + expectedIncome - pendingPayments - averageDailySpending * daysLeft
    }
}
