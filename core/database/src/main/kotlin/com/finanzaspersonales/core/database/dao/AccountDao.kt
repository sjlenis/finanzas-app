package com.finanzaspersonales.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.finanzaspersonales.core.database.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

/** Cuenta con su saldo calculado desde el libro de movimientos. */
data class AccountWithBalance(
    @Embedded val account: AccountEntity,
    @ColumnInfo(name = "balance_centavos") val balanceCentavos: Long,
)

@Dao
interface AccountDao {

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    /**
     * Saldo = saldo inicial + suma de movimientos CONFIRMADOS. Los pendientes de
     * confirmar no alteran el saldo hasta que los apruebes.
     */
    @Query(
        """
        SELECT a.*, a.initial_balance_centavos + COALESCE(SUM(t.amount_centavos), 0) AS balance_centavos
        FROM accounts a
        LEFT JOIN transactions t ON t.account_id = a.id AND t.status = 'CONFIRMED'
        WHERE a.is_archived = 0
        GROUP BY a.id
        ORDER BY a.sort_order, a.id
        """,
    )
    fun observeActiveWithBalance(): Flow<List<AccountWithBalance>>

    /** Todas las cuentas, incluidas las archivadas, con su saldo. */
    @Query(
        """
        SELECT a.*, a.initial_balance_centavos + COALESCE(SUM(t.amount_centavos), 0) AS balance_centavos
        FROM accounts a
        LEFT JOIN transactions t ON t.account_id = a.id AND t.status = 'CONFIRMED'
        GROUP BY a.id
        ORDER BY a.is_archived, a.sort_order, a.id
        """,
    )
    fun observeAllWithBalance(): Flow<List<AccountWithBalance>>

    @Query(
        """
        SELECT a.*, a.initial_balance_centavos + COALESCE(SUM(t.amount_centavos), 0) AS balance_centavos
        FROM accounts a
        LEFT JOIN transactions t ON t.account_id = a.id AND t.status = 'CONFIRMED'
        WHERE a.id = :id
        GROUP BY a.id
        """,
    )
    fun observeWithBalance(id: Long): Flow<AccountWithBalance?>

    @Query(
        """
        SELECT a.initial_balance_centavos + COALESCE(SUM(t.amount_centavos), 0)
        FROM accounts a
        LEFT JOIN transactions t ON t.account_id = a.id AND t.status = 'CONFIRMED'
        WHERE a.id = :id
        GROUP BY a.id
        """,
    )
    suspend fun balanceOf(id: Long): Long?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM accounts")
    suspend fun nextSortOrder(): Int

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    // ---- Eliminar una cuenta (ver AccountRepository.delete) ----

    @Query("SELECT COUNT(*) FROM transactions WHERE account_id = :id")
    suspend fun countTransactions(id: Long): Int

    /**
     * El otro lado de las transferencias con esta cuenta queda como ajuste suelto, para que
     * el saldo de la otra cuenta no cambie al borrar esta.
     */
    @Query(
        """
        UPDATE transactions SET transfer_id = NULL, kind = 'ADJUSTMENT', description = description || ' (cuenta eliminada: ' || :name || ')'
        WHERE account_id != :id AND transfer_id IN (SELECT id FROM transfers WHERE from_account_id = :id OR to_account_id = :id)
        """,
    )
    suspend fun detachTransferCounterparts(id: Long, name: String)

    @Query("UPDATE debt_payments SET transaction_id = NULL WHERE transaction_id IN (SELECT id FROM transactions WHERE account_id = :id)")
    suspend fun clearDebtPaymentLinks(id: Long)

    @Query("UPDATE obligation_payments SET transaction_id = NULL WHERE transaction_id IN (SELECT id FROM transactions WHERE account_id = :id)")
    suspend fun clearObligationPaymentLinks(id: Long)

    @Query("UPDATE obligation_partials SET transaction_id = NULL WHERE transaction_id IN (SELECT id FROM transactions WHERE account_id = :id)")
    suspend fun clearObligationPartialLinks(id: Long)

    @Query("UPDATE chat_messages SET transaction_id = NULL WHERE transaction_id IN (SELECT id FROM transactions WHERE account_id = :id)")
    suspend fun clearChatLinks(id: Long)

    @Query("UPDATE obligations SET account_id = NULL WHERE account_id = :id")
    suspend fun clearObligationAccount(id: Long)

    @Query("DELETE FROM transactions WHERE account_id = :id")
    suspend fun deleteTransactionsOf(id: Long)

    @Query("DELETE FROM transfers WHERE from_account_id = :id OR to_account_id = :id")
    suspend fun deleteTransfersOf(id: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
