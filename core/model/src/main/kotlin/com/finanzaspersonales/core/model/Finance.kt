package com.finanzaspersonales.core.model

import java.time.Instant

/** Cuenta con todos sus datos y su saldo calculado. */
data class Account(
    val id: Long,
    val name: String,
    val type: AccountType,
    val institution: String?,
    val initialBalance: Money,
    val balance: Money,
    val isLiquid: Boolean,
    val isArchived: Boolean,
)

/** Categoría; si [parentId] no es null es una subcategoría. */
data class Category(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val categoryClass: CategoryClass,
    val isEssential: Boolean,
    val isSystem: Boolean,
)

/** Movimiento listo para mostrar, con los nombres de su cuenta y categoría. */
data class Transaction(
    val id: Long,
    val accountId: Long,
    val accountName: String,
    val amount: Money,
    val kind: TransactionKind,
    val categoryId: Long?,
    val categoryName: String?,
    val description: String,
    val occurredAt: Instant,
    val origin: TransactionOrigin,
    val status: TransactionStatus,
    val transferId: Long?,
    val notes: String?,
)

/** Totales de una cuenta en un periodo. */
data class AccountActivity(
    val income: Money,
    val expense: Money,
    val transfersIn: Money,
    val transfersOut: Money,
)

/** Gasto total de una categoría en un periodo. */
data class CategorySpending(val categoryId: Long?, val categoryName: String, val amount: Money)

/**
 * Tipos que se pueden registrar a mano en la Fase 3. El signo del valor guardado
 * lo decide el tipo, nunca el usuario: así un gasto no puede quedar sumando por error.
 */
enum class ManualEntryType(val kind: TransactionKind, val inflow: Boolean, val label: String) {
    EXPENSE(TransactionKind.EXPENSE, inflow = false, label = "Gasto"),
    INCOME(TransactionKind.INCOME, inflow = true, label = "Ingreso"),
    REFUND(TransactionKind.REFUND, inflow = true, label = "Devolución"),
    FEE(TransactionKind.FEE, inflow = false, label = "Comisión"),
    ;

    /** Convierte el valor positivo que escribe el usuario en el valor con signo que se guarda. */
    fun signedAmount(positive: Money): Money {
        require(positive.centavos > 0) { "El valor debe ser mayor que cero" }
        return if (inflow) positive else -positive
    }

    companion object {
        fun fromKind(kind: TransactionKind): ManualEntryType? = entries.firstOrNull { it.kind == kind }
    }
}
