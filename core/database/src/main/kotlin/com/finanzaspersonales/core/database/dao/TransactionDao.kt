package com.finanzaspersonales.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.finanzaspersonales.core.database.entity.AuditLogEntity
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.database.entity.TransferEntity
import kotlinx.coroutines.flow.Flow

/** Totales de ingresos y gastos reales en un rango (sin transferencias, préstamos ni ajustes). */
data class PeriodTotals(
    @ColumnInfo(name = "income_centavos") val incomeCentavos: Long,
    @ColumnInfo(name = "expense_centavos") val expenseCentavos: Long,
)

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert
    suspend fun insertTransfer(transfer: TransferEntity): Long

    @Insert
    suspend fun insertAudit(entry: AuditLogEntity): Long

    @Query("SELECT * FROM transactions ORDER BY occurred_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE transfer_id = :transferId ORDER BY amount_centavos")
    suspend fun getByTransfer(transferId: Long): List<TransactionEntity>

    /**
     * Gastos reales = EXPENSE + FEE (comisiones). Las devoluciones (REFUND) restan del gasto.
     * Las transferencias propias, préstamos y ajustes quedan fuera a propósito.
     */
    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN kind = 'INCOME' THEN amount_centavos ELSE 0 END), 0) AS income_centavos,
          COALESCE(SUM(CASE WHEN kind IN ('EXPENSE', 'FEE', 'REFUND') THEN -amount_centavos ELSE 0 END), 0) AS expense_centavos
        FROM transactions
        WHERE status = 'CONFIRMED' AND occurred_at >= :fromEpochMs AND occurred_at < :toEpochMs
        """,
    )
    fun observeTotals(fromEpochMs: Long, toEpochMs: Long): Flow<PeriodTotals>

    /**
     * Crea una transferencia entre cuentas propias de forma atómica: la cabecera y
     * sus dos movimientos se guardan juntos o no se guarda nada.
     */
    @Transaction
    suspend fun createTransfer(
        fromAccountId: Long,
        toAccountId: Long,
        amountCentavos: Long,
        occurredAtEpochMs: Long,
        description: String,
        nowEpochMs: Long,
    ): Long {
        require(amountCentavos > 0) { "El valor de la transferencia debe ser positivo" }
        require(fromAccountId != toAccountId) { "La cuenta de origen y destino deben ser distintas" }
        val transferId = insertTransfer(
            TransferEntity(
                fromAccountId = fromAccountId,
                toAccountId = toAccountId,
                amountCentavos = amountCentavos,
                occurredAtEpochMs = occurredAtEpochMs,
            ),
        )
        fun leg(accountId: Long, amount: Long) = TransactionEntity(
            accountId = accountId,
            amountCentavos = amount,
            kind = com.finanzaspersonales.core.model.TransactionKind.TRANSFER,
            categoryId = null,
            description = description,
            occurredAtEpochMs = occurredAtEpochMs,
            origin = com.finanzaspersonales.core.model.TransactionOrigin.MANUAL,
            transferId = transferId,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        insert(leg(fromAccountId, -amountCentavos))
        insert(leg(toAccountId, amountCentavos))
        insertAudit(
            AuditLogEntity(
                entityType = "transfer",
                entityId = transferId,
                action = "create",
                detail = null,
                createdAtEpochMs = nowEpochMs,
            ),
        )
        return transferId
    }
}
