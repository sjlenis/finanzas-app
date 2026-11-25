package com.finanzaspersonales.engine.finance

import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.formatCop
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Análisis de hábitos con reglas explícitas y explicables. Todo sale de tus movimientos
 * reales: si no hay datos suficientes, no se dice nada (nunca se inventan cifras).
 */

/** Un gasto real (valor positivo). Excluye transferencias propias, préstamos y pagos de deudas. */
data class Expense(
    val id: Long,
    val date: LocalDate,
    val amount: Money,
    val categoryId: Long?,
    val categoryName: String?,
    val description: String,
    val counterparty: String? = null,
)

data class AntExpenses(
    val threshold: Money,
    val count: Int,
    val total: Money,
    /** Las descripciones que más se repiten, con su total. */
    val top: List<Pair<String, Money>>,
)

data class RecurringExpense(
    val name: String,
    val averageAmount: Money,
    /** Meses distintos en los que apareció. */
    val months: Int,
    val typicalDay: Int,
    val lastDate: LocalDate,
)

data class UnusualExpense(val expense: Expense, val usual: Money, val times: Int)

data class CategoryTrend(
    val categoryId: Long,
    val name: String,
    val essential: Boolean,
    val thisMonth: Money,
    /** A este ritmo, cuánto llevarías al final del mes. */
    val projected: Money,
    val previousAverage: Money,
) {
    val changePercent: Int get() = if (previousAverage.centavos <= 0) 0 else (((projected - previousAverage).centavos * 100) / previousAverage.centavos).toInt()
}

object SpendingPatterns {
    val ANT_THRESHOLD: Money = Money.ofPesos(20_000)
    private const val ANT_MIN_COUNT = 6

    /** Clave para agrupar movimientos parecidos: "Netflix.com 1234" y "NETFLIX" quedan iguales. */
    fun key(text: String): String {
        val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.replace(Regex("[^a-z ]"), " ").trim().split(Regex("\\s+")).filter { it.length > 1 }.take(3).joinToString(" ")
    }

    private fun label(e: Expense): String = (e.counterparty?.takeIf { it.isNotBlank() } ?: e.description).trim()

    /** Gastos hormiga: muchos gastos pequeños en los últimos 30 días que juntos pesan. */
    fun antExpenses(expenses: List<Expense>, today: LocalDate, threshold: Money = ANT_THRESHOLD): AntExpenses? {
        val from = today.minusDays(29)
        val small = expenses.filter { !it.date.isBefore(from) && !it.date.isAfter(today) && it.amount.centavos in 1..threshold.centavos }
        if (small.size < ANT_MIN_COUNT) return null
        val top = small.groupBy { key(label(it)).ifEmpty { "otros" } }
            .map { (_, list) -> label(list.first()).replaceFirstChar { it.uppercase() } to Money(list.sumOf { it.amount.centavos }) }
            .sortedByDescending { it.second.centavos }
            .take(3)
        return AntExpenses(threshold, small.size, Money(small.sumOf { it.amount.centavos }), top)
    }

    /**
     * Pagos que se repiten cada mes (suscripciones, servicios): mismo nombre, valor
     * parecido (±20 % de la mediana) y presente en al menos 3 meses distintos de los últimos 6.
     */
    fun recurring(expenses: List<Expense>, today: LocalDate): List<RecurringExpense> {
        val from = YearMonth.from(today).minusMonths(5).atDay(1)
        return expenses.filter { !it.date.isBefore(from) }
            .groupBy { key(label(it)) }
            .filterKeys { it.isNotEmpty() }
            .mapNotNull { (_, list) ->
                val median = median(list.map { it.amount.centavos })
                val similar = list.filter { kotlin.math.abs(it.amount.centavos - median) * 5 <= median }
                val months = similar.map { YearMonth.from(it.date) }.distinct()
                // Como mucho dos por mes: si es diario (el café) no es una suscripción.
                if (months.size < 3 || similar.size > months.size * 2) return@mapNotNull null
                RecurringExpense(
                    name = label(similar.maxBy { it.date }).replaceFirstChar { it.uppercase() },
                    averageAmount = Money(similar.sumOf { it.amount.centavos } / similar.size),
                    months = months.size,
                    typicalDay = median(similar.map { it.date.dayOfMonth.toLong() }).toInt(),
                    lastDate = similar.maxOf { it.date },
                )
            }
            .sortedByDescending { it.averageAmount.centavos }
    }

    /**
     * Gastos fuera de lo normal en los últimos 30 días: al menos 3 veces lo usual en su
     * categoría (mediana de los meses anteriores, con 5 gastos o más) y de $50.000 o más.
     */
    fun unusual(expenses: List<Expense>, today: LocalDate): List<UnusualExpense> {
        val from = today.minusDays(29)
        val (recent, before) = expenses.partition { !it.date.isBefore(from) }
        val usualByCategory = before.filter { it.categoryId != null }.groupBy { it.categoryId }
            .filterValues { it.size >= 5 }
            .mapValues { (_, list) -> median(list.map { it.amount.centavos }) }
        return recent.mapNotNull { e ->
            val usual = usualByCategory[e.categoryId] ?: return@mapNotNull null
            if (usual <= 0 || e.amount.centavos < Money.ofPesos(50_000).centavos || e.amount.centavos < usual * 3) return@mapNotNull null
            UnusualExpense(e, Money(usual), (e.amount.centavos / usual).toInt())
        }.sortedByDescending { it.expense.amount.centavos }
    }

    /**
     * Categorías en las que vas por encima de tu promedio. Proyecta el mes actual al ritmo
     * de hoy (desde el día 7, antes es muy poco para saber). Reporta subidas del 20 % o más
     * y de al menos $30.000.
     */
    fun trends(
        thisMonth: Map<Long, Money>,
        previousAverage: Map<Long, Money>,
        names: Map<Long, Pair<String, Boolean>>,
        today: LocalDate,
    ): List<CategoryTrend> {
        if (today.dayOfMonth < 7) return emptyList()
        val days = today.lengthOfMonth().toLong()
        return thisMonth.mapNotNull { (id, spent) ->
            val avg = previousAverage[id] ?: return@mapNotNull null
            if (avg.centavos <= 0) return@mapNotNull null
            val projected = Money(Math.multiplyExact(spent.centavos, days) / today.dayOfMonth)
            val diff = projected - avg
            if (diff.centavos < Money.ofPesos(30_000).centavos || diff.centavos * 5 < avg.centavos) return@mapNotNull null
            val (name, essential) = names[id] ?: return@mapNotNull null
            CategoryTrend(id, name, essential, spent, projected, avg)
        }.sortedByDescending { (it.projected - it.previousAverage).centavos }
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

// ------------------------------------------------------------------ simulador de compras

enum class PurchaseVerdict { YES, CAREFUL, WAIT }

data class PurchaseAnalysis(
    val verdict: PurchaseVerdict,
    val price: Money,
    /** Dinero realmente disponible hoy (ya sin pagos pendientes, fondo ni metas). */
    val available: Money,
    /** Lo que necesitas para tus gastos del día a día hasta el próximo ingreso. */
    val dailyNeeds: Money,
    val daysUntilIncome: Int,
    /** Lo que te quedaría libre después de comprar y cubrir el día a día. */
    val leftAfter: Money,
    /** Meses ahorrando tu sobrante promedio para comprarlo sin apretarte; null si no aplica o no hay sobrante. */
    val monthsToSave: Int?,
    val reasons: List<String>,
)

object PurchaseSimulator {
    /** Colchón mínimo que conviene dejar libre además del día a día. */
    val MIN_CUSHION: Money = Money.ofPesos(50_000)

    /**
     * ¿Puedo comprarlo? Nunca cuenta con el fondo de emergencia, las metas ni el dinero de
     * pagos pendientes: parte del dinero realmente disponible y le resta tu gasto diario
     * promedio hasta el próximo ingreso.
     */
    fun analyze(
        price: Money,
        available: Money,
        dailyVariableSpending: Money,
        daysUntilIncome: Int,
        monthlySurplus: Money,
        emergencySaved: Money,
    ): PurchaseAnalysis {
        require(price.centavos > 0) { "Escribe el valor de lo que quieres comprar" }
        val days = daysUntilIncome.coerceAtLeast(0)
        val needs = dailyVariableSpending * days
        val left = available - price - needs
        val cushion = maxOf(MIN_CUSHION, Money(needs.centavos / 4))
        val verdict = when {
            left.isNegative -> PurchaseVerdict.WAIT
            left < cushion -> PurchaseVerdict.CAREFUL
            else -> PurchaseVerdict.YES
        }
        val reasons = mutableListOf<String>()
        reasons += "Tienes ${available.formatCop()} realmente disponibles (ya sin pagos pendientes, fondo de emergencia ni metas)."
        if (days > 0 && needs.centavos > 0) {
            reasons += "Para tus gastos del día a día hasta tu próximo ingreso ($days días) necesitas unos ${needs.formatCop()}."
        }
        when (verdict) {
            PurchaseVerdict.YES -> reasons += "Después de comprarlo te quedarían ${left.formatCop()} libres."
            PurchaseVerdict.CAREFUL -> reasons += "Te alcanza, pero quedarías con solo ${left.formatCop()} libres para imprevistos."
            PurchaseVerdict.WAIT -> reasons += "Te faltarían ${(-left).formatCop()} para cubrir tus gastos hasta el próximo ingreso."
        }
        if (verdict != PurchaseVerdict.YES && emergencySaved.centavos > 0) {
            reasons += "No uses el fondo de emergencia para esto: es para imprevistos."
        }
        val shortfall = (price - maxOf(Money.ZERO, available - needs - cushion)).let { if (it.isNegative) Money.ZERO else it }
        val months = if (verdict != PurchaseVerdict.YES && monthlySurplus.centavos > 0 && shortfall.centavos > 0) {
            ((shortfall.centavos + monthlySurplus.centavos - 1) / monthlySurplus.centavos).toInt()
        } else {
            null
        }
        months?.let { reasons += "Ahorrando tu sobrante promedio (${monthlySurplus.formatCop()} al mes), podrías comprarlo con tranquilidad en ${if (it == 1) "1 mes" else "$it meses"}." }
        if (verdict != PurchaseVerdict.YES && monthlySurplus.centavos <= 0) {
            reasons += "Hoy no te sobra dinero al mes en promedio; antes de esta compra conviene bajar algún gasto."
        }
        return PurchaseAnalysis(verdict, price, available, needs, days, left, months, reasons)
    }
}

// ------------------------------------------------------------------ recomendaciones

enum class InsightSeverity { ALERT, WARNING, TIP }

enum class InsightKind { CASH, BUDGET, TREND, UPCOMING, ANT, UNUSUAL, RECURRING, DEBT, EMERGENCY, SAVINGS }

data class Insight(val kind: InsightKind, val severity: InsightSeverity, val title: String, val body: String)

data class BudgetAlert(val name: String, val spent: Money, val limit: Money)

data class DebtSummary(val name: String, val balance: Money, val rateBasisPoints: Int, val rateType: RateType)

data class UpcomingPayment(val name: String, val amount: Money, val date: LocalDate)

/** Todo lo que el asesor necesita, calculado con tus datos reales. */
data class AdvisorInput(
    val today: LocalDate,
    val monthsAnalyzed: Int,
    val available: Money,
    val projectedEndOfMonth: Money,
    val monthlySurplus: Money,
    val averageEssential: Money,
    val budgetsOver: List<BudgetAlert>,
    val budgetsNear: List<BudgetAlert>,
    val trends: List<CategoryTrend>,
    val upcoming: List<UpcomingPayment>,
    val ants: AntExpenses?,
    val unusual: List<UnusualExpense>,
    /** Recurrentes que todavía no están en el calendario de pagos. */
    val recurringNotInCalendar: List<RecurringExpense>,
    val debts: List<DebtSummary>,
    val emergencySaved: Money,
    val emergencyTarget: Money,
    /** Promedio mensual por categoría no esencial, para sugerir recortes. */
    val nonEssentialAverages: List<Pair<String, Money>>,
)

object Advisor {
    /** Recomendaciones ordenadas por importancia. Cada una explica de dónde sale. */
    fun build(input: AdvisorInput): List<Insight> = buildList {
        val i = input
        if (i.available.isNegative) {
            add(Insight(InsightKind.CASH, InsightSeverity.ALERT, "Te falta dinero para lo comprometido",
                "Lo que tienes en cuentas no cubre tus pagos pendientes y lo apartado: faltan ${(-i.available).formatCop()}. " +
                    "Revisa qué pagos puedes mover y evita gastos que no sean básicos."))
        } else if (i.projectedEndOfMonth.isNegative) {
            add(Insight(InsightKind.CASH, InsightSeverity.WARNING, "A este ritmo terminarías el mes en rojo",
                "La proyección a fin de mes es ${i.projectedEndOfMonth.formatCop()}, contando tus ingresos y pagos previstos " +
                    "y tu gasto diario promedio. Bajar el gasto variable estos días lo evita."))
        }
        i.budgetsOver.forEach { b ->
            add(Insight(InsightKind.BUDGET, InsightSeverity.ALERT, "Te pasaste en ${b.name}",
                "Llevas ${b.spent.formatCop()} de un presupuesto de ${b.limit.formatCop()} (${(b.spent - b.limit).formatCop()} de más)."))
        }
        i.budgetsNear.forEach { b ->
            add(Insight(InsightKind.BUDGET, InsightSeverity.WARNING, "${b.name} está cerca del límite",
                "Llevas ${Round.percent(b.spent, b.limit)} % del presupuesto: te quedan ${(b.limit - b.spent).formatCop()} para el resto del mes."))
        }
        val week = i.upcoming.filter { !it.date.isBefore(i.today) && ChronoUnit.DAYS.between(i.today, it.date) <= 7 }
        if (week.isNotEmpty()) {
            val total = Money(week.sumOf { it.amount.centavos })
            add(Insight(InsightKind.UPCOMING, InsightSeverity.WARNING,
                if (week.size == 1) "Tienes 1 pago esta semana" else "Tienes ${week.size} pagos esta semana",
                week.joinToString(", ") { it.name } + ": ${total.formatCop()} en total. Ya están descontados de tu dinero disponible."))
        }
        i.trends.take(2).forEach { t ->
            add(Insight(InsightKind.TREND, InsightSeverity.WARNING, "Estás gastando más en ${t.name}",
                "Llevas ${t.thisMonth.formatCop()} este mes. A este ritmo llegarías a ${t.projected.formatCop()}, " +
                    "${t.changePercent} % más que tu promedio de ${t.previousAverage.formatCop()}."))
        }
        i.unusual.take(2).forEach { u ->
            add(Insight(InsightKind.UNUSUAL, InsightSeverity.TIP, "Gasto inusual: ${u.expense.description}",
                "${u.expense.amount.formatCop()} el ${u.expense.date.dayOfMonth}/${u.expense.date.monthValue}, unas ${u.times} veces lo usual en " +
                    "${u.expense.categoryName ?: "esa categoría"} (${u.usual.formatCop()}). Si no lo reconoces, revísalo."))
        }
        i.ants?.let { a ->
            val names = a.top.joinToString(", ") { "${it.first} (${it.second.formatCop()})" }
            add(Insight(InsightKind.ANT, InsightSeverity.TIP, "Gastos hormiga: ${a.total.formatCop()} en 30 días",
                "${a.count} gastos de ${a.threshold.formatCop()} o menos. Los que más suman: $names. " +
                    "Reducirlos a la mitad te dejaría unos ${Money(a.total.centavos / 2).formatCop()} más al mes."))
        }
        // Deuda más cara primero (avalancha): solo si hay al menos dos con interés distinto.
        val withRate = i.debts.filter { it.rateType != RateType.NONE && it.rateBasisPoints > 0 && it.balance.centavos > 0 }
        if (i.debts.size >= 2 && withRate.isNotEmpty()) {
            val top = withRate.maxBy { Rates.monthlyRate(it.rateBasisPoints, it.rateType) }
            val pct = Rates.monthlyRate(top.rateBasisPoints, top.rateType).movePointRight(2).setScale(2, java.math.RoundingMode.HALF_UP)
            add(Insight(InsightKind.DEBT, InsightSeverity.TIP, "Prioriza ${top.name}",
                "Es tu deuda más cara (${pct.toPlainString().replace('.', ',')} % mensual). Si te sobra algo, abonarle a esta primero " +
                    "es lo que más intereses te ahorra. Sigue pagando la cuota mínima de las demás."))
        }
        if (i.emergencyTarget.centavos > 0 && i.emergencySaved < i.emergencyTarget) {
            val pct = Round.percent(i.emergencySaved, i.emergencyTarget)
            val suggestion = if (i.monthlySurplus.centavos > 0) Round.downTo(Money(i.monthlySurplus.centavos * 3 / 10), 5_000) else Money.ZERO
            add(Insight(InsightKind.EMERGENCY, InsightSeverity.TIP, "Fondo de emergencia al $pct %",
                if (suggestion.centavos > 0) "Aportar ${suggestion.formatCop()} al mes (30 % de lo que te sobra en promedio) lo hace crecer sin afectar tus gastos básicos."
                else "Llevas ${i.emergencySaved.formatCop()} de ${i.emergencyTarget.formatCop()}. Cuando te sobre dinero al mes, aparta una parte aquí."))
        } else if (i.emergencyTarget.isZero && i.averageEssential.centavos > 0) {
            add(Insight(InsightKind.EMERGENCY, InsightSeverity.TIP, "Configura tu fondo de emergencia",
                "Tus gastos esenciales son unos ${i.averageEssential.formatCop()} al mes. Lo recomendable es tener de 3 a 6 meses de eso guardado."))
        }
        i.nonEssentialAverages.maxByOrNull { it.second.centavos }?.takeIf { it.second.centavos >= Money.ofPesos(50_000).centavos }?.let { (name, avg) ->
            val save = Round.downTo(Money(avg.centavos / 10), 1_000)
            if (save.centavos > 0) {
                add(Insight(InsightKind.SAVINGS, InsightSeverity.TIP, "Podrías ahorrar más reduciendo $name",
                    "Gastas en promedio ${avg.formatCop()} al mes en $name. Bajarlo un 10 % son ${save.formatCop()} al mes, ${(save * 12).formatCop()} al año."))
            }
        }
        i.recurringNotInCalendar.take(3).forEach { r ->
            add(Insight(InsightKind.RECURRING, InsightSeverity.TIP, "Pago recurrente: ${r.name}",
                "Aparece en ${r.months} meses, unos ${r.averageAmount.formatCop()} cerca del día ${r.typicalDay}. " +
                    "Agrégalo al calendario de pagos para que se reserve solo."))
        }
    }
}
