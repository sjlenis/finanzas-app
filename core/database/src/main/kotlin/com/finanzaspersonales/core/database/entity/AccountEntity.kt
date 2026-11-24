package com.finanzaspersonales.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.finanzaspersonales.core.model.AccountType

/**
 * Cuenta o medio de pago. El saldo actual NO se guarda: se calcula como
 * [initialBalanceCentavos] más la suma de los movimientos confirmados.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    /** Entidad financiera (Nequi, Bancolombia...) o null para efectivo/otras. */
    val institution: String?,
    /** Últimos dígitos, para asociar notificaciones. Nunca el número completo. */
    @ColumnInfo(name = "last_digits") val lastDigits: String?,
    @ColumnInfo(name = "initial_balance_centavos") val initialBalanceCentavos: Long,
    @ColumnInfo(name = "initial_balance_at") val initialBalanceAtEpochMs: Long,
    /** Cuenta líquida: su dinero cuenta para el disponible. */
    @ColumnInfo(name = "is_liquid") val isLiquid: Boolean = true,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)
