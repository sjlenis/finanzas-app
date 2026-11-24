package com.finanzaspersonales.engine.nlp

import com.finanzaspersonales.core.common.Period

/**
 * Intérprete por reglas del chat financiero. Es determinista: la misma frase
 * siempre da el mismo resultado, y nunca inventa valores que no estén en el texto.
 */
class MessageParser {

    fun parse(message: String, context: ParseContext): ParseResult {
        val text = normalize(message)
        if (text.isEmpty()) return ParseResult.NotUnderstood("Escribe un gasto, un ingreso o una pregunta.")
        if (text in setOf("ayuda", "help", "que puedes hacer", "hola", "buenas", "buenos dias", "buenas tardes", "buenas noches")) {
            return ParseResult.Help
        }
        if (looksLikeQuestion(message, text)) {
            parseQuery(text, context)?.let { return ParseResult.Query(it) }
        }
        return parseEntry(text, context, display = displayForm(message))
    }

    /**
     * Texto original en minúsculas y con espacios simples, conservando tildes. Tiene la
     * misma longitud que su versión normalizada, así se puede recuperar "Café" a partir de "cafe".
     */
    private fun displayForm(message: String): String =
        java.text.Normalizer.normalize(message.lowercase(), java.text.Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()

    private fun restoreAccents(phrase: String, display: String): String {
        val normalizedDisplay = normalize(display)
        if (normalizedDisplay.length != display.length) return phrase
        val index = normalizedDisplay.indexOf(phrase)
        return if (index >= 0) display.substring(index, index + phrase.length) else phrase
    }

    // ---------------------------------------------------------------- movimientos

    private fun parseEntry(text: String, context: ParseContext, display: String): ParseResult {
        val date = DateExtractor.find(text, context.today)
        // Se quita la fecha antes de buscar el valor para que "el 15" no se lea como $15.
        val withoutDate = date.range?.let { text.replaceRange(it, " ").replace(Regex("\\s+"), " ") } ?: text
        val amount = AmountExtractor.find(withoutDate)
        val kind = detectKind(text) ?: if (amount != null) DraftKind.EXPENSE else null
            ?: return ParseResult.NotUnderstood(
                "No encontré un valor ni una pregunta. Prueba con \"Gasté 15.000 en almuerzo\" o \"¿Cuánto he gastado hoy?\".",
            )
        val account = findAccount(text, context.accountNames)
        val category = CategoryGuesser.guess(text, kind, context.categoryNames)
        val counterparty = if (kind == DraftKind.LOAN_GIVEN || kind == DraftKind.LOAN_RECEIVED) findCounterparty(text) else null
        val withoutAmount = amount?.let { withoutDate.replaceRange(it.range, " ") } ?: withoutDate
        val description = findDescription(withoutAmount, context.accountNames)?.let { restoreAccents(it, display).capitalizeFirst() }
            ?: counterparty?.let { if (kind == DraftKind.LOAN_GIVEN) "Préstamo a $it".capitalizeFirst() else "Préstamo de $it".capitalizeFirst() }
            ?: category
            ?: kind.label
        return ParseResult.Entry(
            EntryDraft(
                kind = kind,
                amount = amount?.money,
                categoryName = category,
                description = description,
                date = date.date,
                accountName = account,
                counterparty = counterparty?.capitalizeFirst(),
            ),
        )
    }

    private fun detectKind(text: String): DraftKind? = when {
        Regex("\\b(me prestaron|me presto|pedi prestad[oa]|me hicieron un prestamo)\\b").containsMatchIn(text) -> DraftKind.LOAN_RECEIVED
        Regex("\\b(le preste|les preste|preste|hice un prestamo)\\b").containsMatchIn(text) -> DraftKind.LOAN_GIVEN
        Regex("\\b(me devolvieron|devolucion|reembolso|me reembolsaron|me reintegraron)\\b").containsMatchIn(text) -> DraftKind.REFUND
        Regex("\\b(retire|saque|retiro)\\b").containsMatchIn(text) && !text.containsWord("compre") -> DraftKind.WITHDRAWAL
        Regex("\\b(comision|cuota de manejo|4 ?x ?1000|gmf)\\b").containsMatchIn(text) &&
            !text.containsWord("recibi") -> DraftKind.FEE
        Regex("\\b(recibi|me pagaron|me pago|gane|me consignaron|me llego|me llegaron|cobre|vendi|me giraron|me transfirieron|ingreso|ingresaron)\\b")
            .containsMatchIn(text) -> DraftKind.INCOME
        Regex("\\b(gaste|pague|compre|me costo|costo|di|invite|almorce|desayune|tanquee|meti)\\b").containsMatchIn(text) -> DraftKind.EXPENSE
        else -> null
    }

    private fun findAccount(text: String, accounts: List<String>): String? {
        accounts.sortedByDescending { it.length }.firstOrNull { text.containsWord(normalize(it)) }?.let { return it }
        if (Regex("\\b(en efectivo|en cash|de contado|con billete)\\b").containsMatchIn(text)) {
            return accounts.firstOrNull { normalize(it) == "efectivo" }
        }
        return null
    }

    private fun findCounterparty(text: String): String? {
        val m = Regex("\\b(?:le preste|me presto|me prestaron|preste)\\s+(?:\\S+\\s+)?(?:pesos\\s+)?(?:a|al|a la)\\s+(?:mi\\s+)?([a-z ]{2,30}?)(?:\\s+(?:hoy|ayer|con|en|por|de|el)\\b|$)")
            .find(text)
            ?: Regex("\\b(?:a|al|a la)\\s+(?:mi\\s+|un\\s+|una\\s+)?(amig[oa]|herman[oa]|mama|papa|prim[oa]|ti[oa]|novi[oa]|vecin[oa]|compañer[oa]|[a-z]{3,15})\\b").find(text)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it !in setOf("pesos", "mil") }
    }

    private val stopTail = Regex("\\s+(?:hoy|ayer|antier|con|en efectivo|por nequi|por bancolombia|desde|y|que|pesos)\\b.*$")
    private val leadingArticle = Regex("^(?:un|una|el|la|los|las|unos|unas|mi|mis)\\s+")

    /** "gasté 15.000 en almuerzo con nequi" -> "Almuerzo". */
    private fun findDescription(text: String, accounts: List<String>): String? {
        val m = Regex("\\b(?:en|de|por|para)\\s+([a-z ][a-z ]{1,40})").findAll(text)
        val accountWords = accounts.map { normalize(it) }.toSet() + setOf("efectivo", "pesos", "mil", "plata")
        for (match in m) {
            var phrase = match.groupValues[1].trim()
            phrase = phrase.replace(stopTail, "").trim().replace(leadingArticle, "").trim()
            if (phrase.isEmpty() || phrase in accountWords || phrase.split(' ').all { it in accountWords }) continue
            if (phrase.length < 2) continue
            return phrase.split(' ').take(5).joinToString(" ")
        }
        return null
    }

    // ---------------------------------------------------------------- consultas

    private fun looksLikeQuestion(original: String, text: String): Boolean =
        original.contains('?') || original.contains('¿') ||
            Regex("^(cuanto|cuanta|cuales|cual|como|que|en que|tengo|dime|muestrame|resumen)\\b").containsMatchIn(text)

    private fun parseQuery(text: String, context: ParseContext): FinanceQuery? {
        val period = when {
            text.containsWord("hoy") -> Period.DAY
            Regex("\\b(esta semana|la semana|semana)\\b").containsMatchIn(text) -> Period.WEEK
            Regex("\\b(este ano|el ano|en el ano|ano)\\b").containsMatchIn(text) -> Period.YEAR
            else -> Period.MONTH
        }
        return when {
            Regex("\\b(puedo gastar|me queda para gastar|cuanto puedo)\\b").containsMatchIn(text) -> FinanceQuery.WeeklyAllowance
            Regex("\\b(obligaciones|proximos pagos|pagos pendientes|que tengo que pagar|que debo pagar|proximas cuotas)\\b").containsMatchIn(text) ->
                FinanceQuery.UpcomingPayments
            Regex("\\b(metas|meta de ahorro|ahorros|como va mi ahorro|como van mis)\\b").containsMatchIn(text) -> FinanceQuery.SavingsGoals
            Regex("\\b(mas altos|mas grandes|mayores gastos|en que he gastado mas|en que gaste mas|gastos mas)\\b").containsMatchIn(text) ->
                FinanceQuery.TopExpenses(period)
            Regex("\\b(gastado|gaste|gastos|gasto|llevo)\\b").containsMatchIn(text) ->
                FinanceQuery.Spent(period, CategoryGuesser.guess(text, DraftKind.EXPENSE, context.categoryNames))
            Regex("\\b(recibido|recibi|ingresos|me han pagado|me pagaron|ganado|entrado|entro)\\b").containsMatchIn(text) ->
                FinanceQuery.Received(period)
            text.containsWord("efectivo") -> FinanceQuery.Cash
            else -> {
                val account = context.accountNames.sortedByDescending { it.length }.firstOrNull { text.containsWord(normalize(it)) }
                when {
                    account != null -> FinanceQuery.AccountBalance(account)
                    Regex("\\b(disponible|tengo|saldo|plata|dinero)\\b").containsMatchIn(text) -> FinanceQuery.TotalMoney
                    else -> null
                }
            }
        }
    }
}
