package com.finanzaspersonales.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Transferencia entre cuentas propias. Siempre va acompañada de dos movimientos
 * (salida y entrada) con el mismo [id] en `transfer_id`, creados en una sola transacción SQL.
 */
@Entity(
    tableName = "transfers",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["from_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["to_account_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("from_account_id"), Index("to_account_id")],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "from_account_id") val fromAccountId: Long,
    @ColumnInfo(name = "to_account_id") val toAccountId: Long,
    @ColumnInfo(name = "amount_centavos") val amountCentavos: Long,
    @ColumnInfo(name = "occurred_at") val occurredAtEpochMs: Long,
    val note: String? = null,
)
