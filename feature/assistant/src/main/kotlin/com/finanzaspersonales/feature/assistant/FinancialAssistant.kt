package com.finanzaspersonales.feature.assistant

import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.common.instantForDate
import com.finanzaspersonales.core.data.TransactionRepository
import com.finanzaspersonales.core.designsystem.component.formatShortDate
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.CategoryClass
import com.finanzaspersonales.core.model.ManualEntryType
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.core.model.sum
import com.finanzaspersonales.engine.nlp.DraftKind
import com.finanzaspersonales.engine.nlp.EntryDraft
import com.finanzaspersonales.engine.nlp.FinanceQuery
import com.finanzaspersonales.engine.nlp.MessageParser
import com.finanzaspersonales.engine.nlp.ParseContext
import com.finanzaspersonales.engine.nlp.ParseResult
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** Datos actuales del usuario con los que el asistente interpreta y responde. */
data class AssistantData(
    val accounts: List<Account>,
    val categories: List<Category>,
    val defaultAccountId: Long?,
) {
    /** Cuenta predeterminada: la elegida por el usuario o, si no hay, la primera activa. */
    val defaultAccount: Account?
        get() = accounts.firstOrNull { it.id == defaultAccountId } ?: accounts.firstOrNull()
}

/** Respuesta del asistente: un texto y, si es un registro, una tarjeta para confirmar. */
data class AssistantReply(val text: String, val card: DraftCard? = null, val status: CardStatus? = null)

/**
 * Cerebro del chat, sin interfaz: interpreta con reglas locales ([MessageParser]),
 * responde con datos reales de la base y guarda solo lo que el usuario confirma.
 * Nada sale del teléfono.
 */
class FinancialAssistant @Inject constructor(
    private val transactions: TransactionRepository,
    private val planning: com.finanzaspersonales.core.data.PlanningRepository,
    private val overview: com.finanzaspersonales.core.data.OverviewRepository,
    private val insights: com.finanzaspersonales.core.data.InsightsRepository,
    private val stats: com.finanzaspersonales.core.data.StatisticsRepository,
    private val clock: Clock,
) {
    private val parser = MessageParser()

    fun today(): LocalDate = BogotaTime.today(clock)

    suspend fun reply(message: String, data: AssistantData): AssistantReply {
        val context = ParseContext(
            today = today(),
            accountNames = data.accounts.map { it.name },
            categoryNames = data.categories.map { it.name },
        )
        return when (val result = parser.parse(message, context)) {
            is ParseResult.Entry -> proposal(result.draft, data)
            is ParseResult.Query -> AssistantReply(answer(result.query, data))
            ParseResult.Help -> AssistantReply(HELP)
            is ParseResult.NotUnderstood -> AssistantReply(result.hint)
        }
    }

    /** Si el mensaje es solo un valor ("15.000", "fueron 20 mil") lo devuelve; si no, null. */
    fun amountReply(message: String) = parser.parseAmountReply(message)

    // ------------------------------------------------------------------ registros

    private fun proposal(draft: EntryDraft, data: AssistantData): AssistantReply {
        val card = resolve(draft, data)
        if (card.accountId == null) {
            return AssistantReply(
                if (card.kind == DraftKind.WITHDRAWAL) "Para registrar un retiro necesito una cuenta bancaria activa y una de efectivo."
                else "No tienes cuentas activas. Crea una en Perfil > Cuentas para poder registrar movimientos.",
            )
        }
        return if (card.amount == null) {
            AssistantReply("¿Cuánto fue? Escríbeme solo el valor, por ejemplo 15.000.", card, CardStatus.AWAITING_AMOUNT)
        } else {
            AssistantReply("Esto es lo que entendí. Revísalo antes de guardar:", card, CardStatus.PENDING)
        }
    }

    fun resolve(draft: EntryDraft, data: AssistantData): DraftCard {
        val named = draft.accountName?.let { name -> data.accounts.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val category = categoryFor(draft, data.categories)
        if (draft.kind == DraftKind.WITHDRAWAL) {
            val cash = data.accounts.firstOrNull { it.type == AccountType.CASH }
            val source = named?.takeIf { it.type != AccountType.CASH }
                ?: data.defaultAccount?.takeIf { it.type != AccountType.CASH }
                ?: data.accounts.firstOrNull { it.type != AccountType.CASH }
            val ok = cash != null && source != null
            return DraftCard(
                kind = draft.kind,
                amount = draft.amount,
                categoryId = null,
                categoryName = null,
                description = "Retiro a efectivo",
                date = draft.date,
                accountId = if (ok) source?.id else null,
                accountName = source?.name,
                accountIsDefault = named == null,
                toAccountId = cash?.id,
                toAccountName = cash?.name,
            )
        }
        val account = named ?: data.defaultAccount
        return DraftCard(
            kind = draft.kind,
            amount = draft.amount,
            categoryId = category?.id,
            categoryName = category?.name,
            description = draft.description,
            date = draft.date,
            accountId = account?.id,
            accountName = account?.name,
            accountIsDefault = named == null,
            counterparty = draft.counterparty,
        )
    }

    private fun categoryFor(draft: EntryDraft, categories: List<Category>): Category? {
        val wanted = classFor(draft.kind) ?: return null
        val byName = draft.categoryName?.let { name -> categories.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        if (byName != null && byName.categoryClass == wanted) return byName
        if (wanted == CategoryClass.NEUTRAL) return categories.firstOrNull { it.name == "Préstamos" }
        return null
    }

    /** Guarda una tarjeta confirmada. Devuelve el id del movimiento o lanza un error con un mensaje claro. */
    suspend fun save(card: DraftCard, data: AssistantData): Long {
        val amount = requireNotNull(card.amount) { "Falta el valor" }
        require(amount.centavos > 0) { "El valor debe ser mayor que cero" }
        require(!card.date.isAfter(today())) { "La fecha no puede ser futura" }
        val account = requireNotNull(data.accounts.firstOrNull { it.id == card.accountId }) { "La cuenta ya no existe o está archivada" }
        val occurredAt = BogotaTime.instantForDate(card.date, clock)
        return when (card.kind) {
            DraftKind.EXPENSE, DraftKind.INCOME, DraftKind.REFUND, DraftKind.FEE -> transactions.saveManual(
                id = null,
                type = ManualEntryType.valueOf(card.kind.name),
                amount = amount,
                accountId = account.id,
                categoryId = card.categoryId,
                description = card.description,
                occurredAt = occurredAt,
                notes = null,
                origin = TransactionOrigin.CHAT,
            )
            DraftKind.LOAN_GIVEN, DraftKind.LOAN_RECEIVED -> transactions.saveLoan(
                given = card.kind == DraftKind.LOAN_GIVEN,
                amount = amount,
                accountId = account.id,
                categoryId = card.categoryId,
                description = card.description,
                counterparty = card.counterparty,
                occurredAt = occurredAt,
                origin = TransactionOrigin.CHAT,
            )
            DraftKind.WITHDRAWAL -> {
                val cash = requireNotNull(data.accounts.firstOrNull { it.id == card.toAccountId }) { "No encontré la cuenta de efectivo" }
                require(cash.id != account.id) { "La cuenta de origen debe ser distinta de la de efectivo" }
                transactions.saveTransfer(null, account.id, cash.id, amount, card.description, occurredAt)
            }
        }
    }

    fun savedText(card: DraftCard): String {
        val amount = card.amount?.formatCop().orEmpty()
        val whenText = if (card.date == today()) "hoy" else formatShortDate(card.date)
        return when (card.kind) {
            DraftKind.WITHDRAWAL -> "Guardado: retiro de $amount de ${card.accountName} a ${card.toAccountName}, $whenText."
            DraftKind.LOAN_GIVEN, DraftKind.LOAN_RECEIVED ->
                "Guardado: ${card.kind.label.lowercase()} de $amount en ${card.accountName}, $whenText. " +
                    "Mueve el saldo, pero no cuenta como ingreso ni gasto. Si es una deuda con cuotas, regístrala en Perfil > Deudas para seguirla."
            else -> "Guardado: ${card.kind.label.lowercase()} de $amount" +
                (card.categoryName?.let { " en $it" } ?: "") + " desde ${card.accountName}, $whenText."
        }
    }

    // ------------------------------------------------------------------ consultas

    suspend fun answer(query: FinanceQuery, data: AssistantData): String {
        val today = today()
        return when (query) {
            is FinanceQuery.Spent -> {
                val range = query.period.rangeContaining(today)
                val categoryName = query.categoryName
                if (categoryName == null) {
                    val spent = transactions.totals(range).expense
                    if (spent.isZero) "${phrase(query.period).capitalized()} no tienes gastos registrados."
                    else "${phrase(query.period).capitalized()} has gastado ${spent.formatCop()}."
                } else {
                    val category = data.categories.firstOrNull { it.name.equals(categoryName, ignoreCase = true) }
                        ?: return "No tengo una categoría llamada $categoryName."
                    val spent = transactions.spentIn(range, category.id)
                    "${phrase(query.period).capitalized()} has gastado ${spent.formatCop()} en ${category.name}."
                }
            }
            is FinanceQuery.Received -> {
                val income = transactions.totals(query.period.rangeContaining(today)).income
                if (income.isZero) "${phrase(query.period).capitalized()} no tienes ingresos registrados."
                else "${phrase(query.period).capitalized()} has recibido ${income.formatCop()} en ingresos."
            }
            FinanceQuery.TotalMoney -> {
                val liquid = data.accounts.filter { it.isLiquid }
                if (liquid.isEmpty()) return "No tienes cuentas activas."
                buildString {
                    append("Tienes ${liquid.map { it.balance }.sum().formatCop()} en tus cuentas:")
                    liquid.forEach { append("\n• ${it.name}: ${it.balance.formatCop()}") }
                    append("\nSon saldos calculados con lo que has registrado.")
                }
            }
            FinanceQuery.Cash -> {
                val cash = data.accounts.filter { it.type == AccountType.CASH }
                if (cash.isEmpty()) "No tienes una cuenta de efectivo." else "Tienes ${cash.map { it.balance }.sum().formatCop()} en efectivo."
            }
            is FinanceQuery.AccountBalance -> {
                val account = data.accounts.firstOrNull { it.name.equals(query.accountName, ignoreCase = true) }
                    ?: return "No encontré la cuenta ${query.accountName}."
                "El saldo calculado de ${account.name} es ${account.balance.formatCop()}. " +
                    "Si no coincide con tu banco, concílialo en Perfil > Cuentas."
            }
            is FinanceQuery.TopExpenses -> {
                val top = transactions.spendingByCategory(query.period.rangeContaining(today)).take(3)
                if (top.isEmpty()) "${phrase(query.period).capitalized()} no tienes gastos registrados."
                else buildString {
                    append("Tus mayores gastos ${phrase(query.period)}:")
                    top.forEachIndexed { i, c -> append("\n${i + 1}. ${c.categoryName}: ${c.amount.formatCop()}") }
                }
            }
            FinanceQuery.WeeklyAllowance -> weeklyAllowance(today)
            FinanceQuery.UpcomingPayments -> upcomingPayments()
            FinanceQuery.SavingsGoals -> goalsSummary()
            is FinanceQuery.CanAfford -> canAfford(query.price)
            FinanceQuery.Advice -> advice()
            FinanceQuery.NetWorth -> netWorth()
            FinanceQuery.MonthComparison -> monthComparison()
        }
    }

    private suspend fun weeklyAllowance(today: LocalDate): String {
        val o = overview.observe().first()
        val m = o.money
        val week = transactions.totals(Period.WEEK.rangeContaining(today)).expense
        val reserved = m.obligations + m.emergencyFund + m.goals
        val breakdown = "Tienes ${m.total.formatCop()} en cuentas" +
            (if (reserved.centavos > 0) " y ${reserved.formatCop()} ya comprometidos (pagos pendientes del mes y dinero apartado)" else "") + "."
        val text = if (m.available.centavos <= 0) {
            "$breakdown No te queda dinero libre hasta tu próximo ingreso: lo que hay alcanza solo para lo comprometido" +
                (if (m.available.isNegative) " y te faltan ${m.available.abs().formatCop()}" else "") + "."
        } else {
            val until = o.nextIncome?.let { "hasta tu próximo ingreso (${formatShortDate(it)})" } ?: "hasta fin de mes"
            "Puedes gastar unos ${o.weeklyAllowance.formatCop()} por semana $until. $breakdown"
        }
        val hint = if (o.monthItems.isEmpty()) " Para que el cálculo sea más exacto, agrega tus pagos fijos e ingresos en Perfil > Calendario de pagos." else ""
        return "$text Esta semana llevas ${week.formatCop()} en gastos.$hint"
    }

    private suspend fun upcomingPayments(): String {
        val o = overview.observe().first()
        if (o.monthItems.none { it.type != com.finanzaspersonales.core.data.UpcomingType.INCOME }) {
            return "No tienes pagos registrados. Agrega tus pagos fijos en Perfil > Calendario de pagos y tus deudas en Perfil > Deudas."
        }
        val pending = o.pending
        if (pending.isEmpty()) return "No te quedan pagos pendientes este mes."
        return buildString {
            append("Te quedan ${pending.size} ${if (pending.size == 1) "pago" else "pagos"} este mes por ${o.money.obligations.formatCop()}:")
            pending.forEach { p ->
                val late = if (p.date.isBefore(o.today)) " (vencido)" else ""
                append("\n• ${p.name}: ${p.amount.formatCop()} el ${formatShortDate(p.date)}$late")
            }
        }
    }

    private suspend fun canAfford(price: com.finanzaspersonales.core.model.Money?): String {
        if (price == null) return "¿Cuánto cuesta? Escríbeme por ejemplo: \"¿Puedo comprar un celular de 1.200.000?\""
        val a = insights.simulatePurchase(price)
        val verdict = when (a.verdict) {
            com.finanzaspersonales.engine.finance.PurchaseVerdict.YES -> "Sí, puedes comprarlo sin afectar tus pagos ni tus ahorros."
            com.finanzaspersonales.engine.finance.PurchaseVerdict.CAREFUL -> "Puedes, pero quedarías muy justo."
            com.finanzaspersonales.engine.finance.PurchaseVerdict.WAIT -> "Mejor espera: hoy no te alcanza sin afectar tus pagos o tu día a día."
        }
        return buildString {
            append("${price.formatCop()}: $verdict")
            a.reasons.forEach { append("\n• $it") }
        }
    }

    private suspend fun netWorth(): String {
        val n = stats.report().netWorth
        return buildString {
            append("Tu patrimonio neto es ${n.net.formatCop()}: tienes ${n.assets.formatCop()} en tus cuentas y debes ${n.liabilities.formatCop()}.")
            if (n.debts.isNotEmpty()) n.debts.forEach { append("\n• ${it.first}: ${it.second.formatCop()}") }
            else append(" No tienes deudas registradas.")
        }
    }

    private suspend fun monthComparison(): String {
        val r = stats.report(monthCount = 2)
        val (last, current) = r.months
        if (last.expense.isZero && last.income.isZero) {
            return "No tengo movimientos del mes pasado para comparar. Este mes llevas ${current.expense.formatCop()} en gastos y ${current.income.formatCop()} en ingresos."
        }
        val diff = current.expense - last.expense
        return buildString {
            append("Este mes llevas ${current.expense.formatCop()} en gastos; el mes pasado gastaste ${last.expense.formatCop()} en total")
            append(if (diff.isNegative) " (vas ${(-diff).formatCop()} por debajo)." else " (ya lo superas por ${diff.formatCop()}).")
            append(" Ingresos: ${current.income.formatCop()} este mes y ${last.income.formatCop()} el anterior.")
            r.comparison.filter { it.change.centavos > 0 && it.lastMonth.centavos > 0 }.maxByOrNull { it.change.centavos }?.let {
                append("\nDonde más subió: ${it.name}, ${it.change.formatCop()} más que el mes pasado.")
            }
            append("\nOjo: el mes actual aún no termina.")
        }
    }

    private suspend fun advice(): String {
        val r = insights.report()
        if (r.insights.isEmpty()) {
            return if (r.expensesAnalyzed < 10) {
                "Aún tengo pocos movimientos para analizar tus hábitos (${r.expensesAnalyzed}). Sigue registrando tus gastos y en unas semanas te doy consejos con tus datos."
            } else {
                "Vas bien: no veo alertas ni gastos fuera de lo normal. Sigue así."
            }
        }
        return buildString {
            append("Esto es lo más importante ahora:")
            r.insights.take(4).forEach { append("\n• ${it.title}. ${it.body}") }
            if (r.insights.size > 4) append("\nVe todos los consejos en Perfil > Análisis y consejos.")
        }
    }

    private suspend fun goalsSummary(): String {
        val goals = planning.observeGoals().first()
        if (goals.isEmpty() || goals.all { it.target.isZero && it.saved.isZero }) {
            return "Aún no tienes metas de ahorro ni fondo de emergencia. Créalos en Perfil > Metas de ahorro o Fondo de emergencia."
        }
        return buildString {
            append("Así van tus ahorros:")
            goals.forEach { g ->
                val pct = if (g.target.centavos > 0) (g.saved.centavos * 100 / g.target.centavos).coerceAtMost(100) else 0
                append("\n• ${g.name}: ${g.saved.formatCop()}")
                if (g.target.centavos > 0) append(" de ${g.target.formatCop()} ($pct %)")
            }
        }
    }

    private fun phrase(period: Period) = when (period) {
        Period.DAY -> "hoy"
        Period.WEEK -> "esta semana"
        Period.MONTH -> "este mes"
        Period.YEAR -> "este año"
    }

    private fun String.capitalized() = replaceFirstChar { it.uppercase() }

    companion object {
        fun classFor(kind: DraftKind): CategoryClass? = when (kind) {
            DraftKind.EXPENSE, DraftKind.FEE, DraftKind.REFUND -> CategoryClass.EXPENSE
            DraftKind.INCOME -> CategoryClass.INCOME
            DraftKind.LOAN_GIVEN, DraftKind.LOAN_RECEIVED -> CategoryClass.NEUTRAL
            DraftKind.WITHDRAWAL -> null
        }

        const val WELCOME = "Hola. Escríbeme tus gastos e ingresos como se los contarías a alguien, " +
            "por ejemplo \"Hoy gasté 15.000 en almuerzo\". Te muestro una tarjeta y solo guardo cuando confirmes. " +
            "Todo se procesa en tu teléfono."

        const val HELP = "Puedo registrar y responder cosas como:\n" +
            "• \"Gasté 18 mil en almuerzo con Nequi\"\n" +
            "• \"Me pagaron 2.500.000 de salario\"\n" +
            "• \"Retiré 200 mil del cajero\"\n" +
            "• \"Le presté 50 mil a mi hermano\"\n" +
            "• \"¿Cuánto he gastado hoy?\" o \"¿Cuánto llevo este mes en transporte?\"\n" +
            "• \"¿Cuánto tengo en Nequi?\" o \"¿Cuáles son mis gastos más altos?\"\n" +
            "• \"¿Puedo comprar un celular de 1.200.000?\" o \"Dame consejos\"\n" +
            "• Con el botón de la cámara registras deudas, pagos o facturas desde una foto.\n" +
            "Siempre te muestro una tarjeta antes de guardar."
    }
}
