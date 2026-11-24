package com.finanzaspersonales.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.TransactionStatus

/**
 * Movimiento de una cuenta. [amountCentavos] lleva signo: positivo entra dinero,
 * negativo sale. Las columnas de deuda, préstamo, meta y obligación se añadirán
 * con migraciones en las fases 3 y 6.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = TransferEntity::class, parentColumns = ["id"], childColumns = ["transfer_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("account_id"),
        Index("category_id"),
        Index("transfer_id"),
        Index("occurred_at"),
        // Evita guardar dos veces el mismo movimiento detectado (misma huella).
        Index(value = ["fingerprint"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "amount_centavos") val amountCentavos: Long,
    val kind: TransactionKind,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    val description: String,
    val counterparty: String? = null,
    @ColumnInfo(name = "occurred_at") val occurredAtEpochMs: Long,
    val origin: TransactionOrigin,
    val status: TransactionStatus = TransactionStatus.CONFIRMED,
    @ColumnInfo(name = "transfer_id") val transferId: Long? = null,
    /** Huella para detectar duplicados; null en registros manuales. */
    val fingerprint: String? = null,
    val notes: String? = null,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMs: Long,
)
