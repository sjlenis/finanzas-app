package com.finanzaspersonales.engine.nlp

import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.model.Money
import java.time.LocalDate

/** Tipo de movimiento que el asistente puede proponer. */
enum class DraftKind(val label: String) {
    EXPENSE("Gasto"),
    INCOME("Ingreso"),
    REFUND("Devolución"),
    FEE("Comisión"),
    LOAN_GIVEN("Préstamo que hiciste"),
    LOAN_RECEIVED("Préstamo que recibiste"),
    /** Retiro de cajero: transferencia de una cuenta bancaria a Efectivo. */
    WITHDRAWAL("Retiro a efectivo"),
}

/**
 * Lo que el intérprete entendió de un mensaje. Los campos null son los que el
 * mensaje no decía; la app los completa (cuenta predeterminada) o los pregunta.
 */
data class EntryDraft(
    val kind: DraftKind,
    val amount: Money?,
    val categoryName: String?,
    val description: String,
    val date: LocalDate,
    val accountName: String?,
    val counterparty: String?,
)

/** Preguntas que el asistente sabe responder con datos reales. */
sealed interface FinanceQuery {
    data class Spent(val period: Period, val categoryName: String?) : FinanceQuery
    data class Received(val period: Period) : FinanceQuery
    data object TotalMoney : FinanceQuery
    data object Cash : FinanceQuery
    data class AccountBalance(val accountName: String) : FinanceQuery
    data class TopExpenses(val period: Period) : FinanceQuery
    data object WeeklyAllowance : FinanceQuery
    data object UpcomingPayments : FinanceQuery
    data object SavingsGoals : FinanceQuery
}

sealed interface ParseResult {
    data class Entry(val draft: EntryDraft) : ParseResult
    data class Query(val query: FinanceQuery) : ParseResult
    data object Help : ParseResult
    /** No se entendió; [hint] explica qué faltó. */
    data class NotUnderstood(val hint: String) : ParseResult
}

/** Datos del usuario que el intérprete necesita: fecha de hoy y nombres reales. */
data class ParseContext(
    val today: LocalDate,
    val accountNames: List<String>,
    val categoryNames: List<String>,
)
