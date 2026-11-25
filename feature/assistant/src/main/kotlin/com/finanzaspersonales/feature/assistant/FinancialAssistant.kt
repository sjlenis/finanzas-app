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
                    "Mueve el saldo, pero no cuenta como ingreso ni gasto. El seguimiento de deudas llega en la Fase 6."
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
            FinanceQuery.WeeklyAllowance -> {
                val week = transactions.totals(Period.WEEK.rangeContaining(today)).expense
                "Todavía no puedo decirte cuánto puedes gastar: para eso necesito tus presupuestos y obligaciones, " +
                    "que llegan en la Fase 6. Lo que sí sé: esta semana llevas ${week.formatCop()} en gastos."
            }
            FinanceQuery.UpcomingPayments -> upcomingDebtPayments(today)
            FinanceQuery.SavingsGoals -> goalsSummary()
        }
    }

    private suspend fun upcomingDebtPayments(today: LocalDate): String {
        val debts = planning.observeDebts().first()
            .filter { it.status == com.finanzaspersonales.core.data.DebtStatus.ACTIVE && it.dueDay != null }
            .sortedBy { d -> nextDue(d.dueDay!!, today) }
        if (debts.isEmpty()) {
            return "No tienes cuotas de deudas con día de pago registradas. Agrégalas en Perfil > Deudas. " +
                "El calendario de otros pagos (arriendo, servicios) llega en la siguiente entrega."
        }
        return buildString {
            append("Tus próximas cuotas:")
            debts.forEach { d -> append("\n• ${d.name}: ${d.monthlyPayment.formatCop()} el ${formatShortDate(nextDue(d.dueDay!!, today))}") }
        }
    }

    private fun nextDue(day: Int, today: LocalDate): LocalDate {
        val thisMonth = today.withDayOfMonth(minOf(day, today.lengthOfMonth()))
        if (!thisMonth.isBefore(today)) return thisMonth
        val next = today.plusMonths(1)
        return next.withDayOfMonth(minOf(day, next.lengthOfMonth()))
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
            "Siempre te muestro una tarjeta antes de guardar."
    }
}
