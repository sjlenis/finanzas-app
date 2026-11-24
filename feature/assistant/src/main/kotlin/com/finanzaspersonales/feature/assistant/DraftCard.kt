package com.finanzaspersonales.feature.assistant

import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.engine.nlp.DraftKind
import org.json.JSONObject
import java.time.LocalDate

/** Estado de una tarjeta de confirmación. Nada se guarda hasta que la confirmes. */
enum class CardStatus {
    /** Falta el valor: el asistente lo está preguntando. */
    AWAITING_AMOUNT,
    PENDING,
    SAVED,
    CANCELLED,
    ;

    companion object {
        fun from(raw: String?): CardStatus? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * Borrador ya resuelto contra los datos del usuario (ids reales de cuenta y categoría).
 * Es lo que muestra la tarjeta y lo que se guarda si la confirmas.
 */
data class DraftCard(
    val kind: DraftKind,
    val amount: Money?,
    val categoryId: Long?,
    val categoryName: String?,
    val description: String,
    val date: LocalDate,
    val accountId: Long?,
    val accountName: String?,
    /** true si la cuenta salió de la cuenta predeterminada y no del mensaje. */
    val accountIsDefault: Boolean,
    /** Solo para retiros: la cuenta de efectivo que recibe el dinero. */
    val toAccountId: Long? = null,
    val toAccountName: String? = null,
    val counterparty: String? = null,
) {
    val usesCategory: Boolean get() = kind != DraftKind.WITHDRAWAL

    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("kind", kind.name)
        amount?.let { put("amount", it.centavos) }
        categoryId?.let { put("categoryId", it) }
        categoryName?.let { put("categoryName", it) }
        put("description", description)
        put("date", date.toString())
        accountId?.let { put("accountId", it) }
        accountName?.let { put("accountName", it) }
        put("accountIsDefault", accountIsDefault)
        toAccountId?.let { put("toAccountId", it) }
        toAccountName?.let { put("toAccountName", it) }
        counterparty?.let { put("counterparty", it) }
    }.toString()

    companion object {
        /** Devuelve null si el texto guardado está dañado (la tarjeta no se muestra, pero el chat sigue). */
        fun fromJson(json: String): DraftCard? = runCatching {
            val o = JSONObject(json)
            DraftCard(
                kind = DraftKind.valueOf(o.getString("kind")),
                amount = if (o.has("amount")) Money(o.getLong("amount")) else null,
                categoryId = if (o.has("categoryId")) o.getLong("categoryId") else null,
                categoryName = o.optString("categoryName").ifEmpty { null },
                description = o.getString("description"),
                date = LocalDate.parse(o.getString("date")),
                accountId = if (o.has("accountId")) o.getLong("accountId") else null,
                accountName = o.optString("accountName").ifEmpty { null },
                accountIsDefault = o.optBoolean("accountIsDefault"),
                toAccountId = if (o.has("toAccountId")) o.getLong("toAccountId") else null,
                toAccountName = o.optString("toAccountName").ifEmpty { null },
                counterparty = o.optString("counterparty").ifEmpty { null },
            )
        }.getOrNull()
    }
}
