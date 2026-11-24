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

/** Movimiento con los nombres de su cuenta y categoría (para listas). */
data class TransactionRow(
    @androidx.room.Embedded val transaction: TransactionEntity,
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "category_name") val categoryName: String?,
)

/** Totales de una cuenta en un rango. */
data class AccountActivityRow(
    @ColumnInfo(name = "income_centavos") val incomeCentavos: Long,
    @ColumnInfo(name = "expense_centavos") val expenseCentavos: Long,
    @ColumnInfo(name = "transfers_in_centavos") val transfersInCentavos: Long,
    @ColumnInfo(name = "transfers_out_centavos") val transfersOutCentavos: Long,
)

/** Gasto neto por categoría principal (las subcategorías suman en su categoría padre). */
data class CategorySpendingRow(
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "amount_centavos") val amountCentavos: Long,
)

@Dao
interface TransactionDao {

    @androidx.room.Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transfers WHERE id = :transferId")
    suspend fun deleteTransferById(transferId: Long)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun getTransfer(id: Long): TransferEntity?

    /**
     * Historial filtrado. Los filtros null no se aplican. Si se filtra por categoría
     * principal, también trae sus subcategorías.
     */
    @Query(
        """
        SELECT t.*, a.name AS account_name, c.name AS category_name
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.occurred_at >= :fromEpochMs AND t.occurred_at < :toEpochMs
          AND (:accountId IS NULL OR t.account_id = :accountId)
          AND (:categoryId IS NULL OR t.category_id = :categoryId OR c.parent_id = :categoryId)
        ORDER BY t.occurred_at DESC, t.id DESC
        """,
    )
    fun observeFiltered(fromEpochMs: Long, toEpochMs: Long, accountId: Long?, categoryId: Long?): Flow<List<TransactionRow>>

    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN kind = 'INCOME' THEN amount_centavos ELSE 0 END), 0) AS income_centavos,
          COALESCE(SUM(CASE WHEN kind IN ('EXPENSE', 'FEE', 'REFUND') THEN -amount_centavos ELSE 0 END), 0) AS expense_centavos,
          COALESCE(SUM(CASE WHEN kind = 'TRANSFER' AND amount_centavos > 0 THEN amount_centavos ELSE 0 END), 0) AS transfers_in_centavos,
          COALESCE(SUM(CASE WHEN kind = 'TRANSFER' AND amount_centavos < 0 THEN -amount_centavos ELSE 0 END), 0) AS transfers_out_centavos
        FROM transactions
        WHERE status = 'CONFIRMED' AND account_id = :accountId
          AND occurred_at >= :fromEpochMs AND occurred_at < :toEpochMs
        """,
    )
    fun observeAccountActivity(accountId: Long, fromEpochMs: Long, toEpochMs: Long): Flow<AccountActivityRow>

    @Query(
        """
        SELECT COALESCE(p.id, c.id) AS category_id, COALESCE(p.name, c.name) AS category_name,
               SUM(-t.amount_centavos) AS amount_centavos
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.category_id
        LEFT JOIN categories p ON p.id = c.parent_id
        WHERE t.status = 'CONFIRMED' AND t.kind IN ('EXPENSE', 'FEE', 'REFUND')
          AND t.occurred_at >= :fromEpochMs AND t.occurred_at < :toEpochMs
        GROUP BY COALESCE(p.id, c.id)
        HAVING SUM(-t.amount_centavos) > 0
        ORDER BY amount_centavos DESC
        """,
    )
    fun observeSpendingByCategory(fromEpochMs: Long, toEpochMs: Long): Flow<List<CategorySpendingRow>>

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    /** Para movimientos detectados: si la huella ya existe devuelve -1 y no guarda nada. */
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(transaction: TransactionEntity): Long

    /** Movimientos pendientes de confirmar, del más reciente al más antiguo. */
    @Query(
        """
        SELECT t.*, a.name AS account_name, c.name AS category_name
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.status = 'PENDING'
        ORDER BY t.occurred_at DESC, t.id DESC
        """,
    )
    fun observePending(): Flow<List<TransactionRow>>

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** Otro movimiento del mismo valor en la misma cuenta y en la misma ventana de tiempo. */
    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE account_id = :accountId AND amount_centavos = :amountCentavos
          AND occurred_at >= :fromEpochMs AND occurred_at < :toEpochMs
        """,
    )
    suspend fun countSimilar(accountId: Long, amountCentavos: Long, fromEpochMs: Long, toEpochMs: Long): Int

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
