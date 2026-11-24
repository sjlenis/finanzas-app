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

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?
}
