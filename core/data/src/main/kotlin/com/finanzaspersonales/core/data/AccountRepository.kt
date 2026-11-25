package com.finanzaspersonales.core.data

import androidx.room.withTransaction
import com.finanzaspersonales.core.common.ClosedOpenRange
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.database.entity.AccountEntity
import com.finanzaspersonales.core.database.entity.AuditLogEntity
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.model.Account
import com.finanzaspersonales.core.model.AccountActivity
import com.finanzaspersonales.core.model.AccountType
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.formatCop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val db: FinanzasDatabase,
    private val clock: Clock,
) {
    private val accountDao get() = db.accountDao()
    private val transactionDao get() = db.transactionDao()

    fun observeAccounts(includeArchived: Boolean = false): Flow<List<Account>> =
        (if (includeArchived) accountDao.observeAllWithBalance() else accountDao.observeActiveWithBalance())
            .map { rows -> rows.map { it.toModel() } }

    fun observeAccount(id: Long): Flow<Account?> = accountDao.observeWithBalance(id).map { it?.toModel() }

    fun observeActivity(accountId: Long, range: ClosedOpenRange): Flow<AccountActivity> =
        transactionDao.observeAccountActivity(accountId, range.start.toEpochMilli(), range.endExclusive.toEpochMilli())
            .map {
                AccountActivity(
                    income = Money(it.incomeCentavos),
                    expense = Money(it.expenseCentavos),
                    transfersIn = Money(it.transfersInCentavos),
                    transfersOut = Money(it.transfersOutCentavos),
                )
            }

    suspend fun create(name: String, type: AccountType, institution: String?, initialBalance: Money): Long =
        db.withTransaction {
            val now = clock.millis()
            val id = accountDao.insert(
                AccountEntity(
                    name = name.trim(),
                    type = type,
                    institution = institution?.trim()?.ifEmpty { null },
                    lastDigits = null,
                    initialBalanceCentavos = initialBalance.centavos,
                    initialBalanceAtEpochMs = now,
                    sortOrder = accountDao.nextSortOrder(),
                ),
            )
            audit("account", id, "create", "Saldo inicial ${initialBalance.formatCop()}", now)
            id
        }

    /**
     * Cambia nombre, tipo o saldo inicial. Cambiar el saldo inicial recalcula el saldo
     * actual; para cuadrar con el banco es mejor [reconcile], que deja rastro del ajuste.
     */
    suspend fun update(id: Long, name: String, type: AccountType, institution: String?, initialBalance: Money) =
        db.withTransaction {
            val current = requireNotNull(accountDao.getById(id)) { "La cuenta no existe" }
            accountDao.update(
                current.copy(
                    name = name.trim(),
                    type = type,
                    institution = institution?.trim()?.ifEmpty { null },
                    initialBalanceCentavos = initialBalance.centavos,
                ),
            )
            val detail = if (current.initialBalanceCentavos != initialBalance.centavos) {
                "Saldo inicial ${Money(current.initialBalanceCentavos).formatCop()} -> ${initialBalance.formatCop()}"
            } else {
                null
            }
            audit("account", id, "update", detail, clock.millis())
        }

    suspend fun setArchived(id: Long, archived: Boolean) = db.withTransaction {
        val current = requireNotNull(accountDao.getById(id)) { "La cuenta no existe" }
        accountDao.update(current.copy(isArchived = archived))
        audit("account", id, if (archived) "archive" else "unarchive", null, clock.millis())
    }

    /** Cuántos movimientos se borrarían junto con la cuenta. */
    suspend fun movementCount(id: Long): Int = accountDao.countTransactions(id)

    /**
     * Elimina la cuenta y sus movimientos. Las transferencias con otras cuentas no cambian el
     * saldo de esas otras cuentas: su lado queda como un ajuste (no es ingreso ni gasto).
     * Los pagos fijos o de deudas marcados siguen marcados, solo pierden el enlace al movimiento.
     */
    suspend fun delete(id: Long) = db.withTransaction {
        val account = requireNotNull(accountDao.getById(id)) { "La cuenta no existe" }
        val count = accountDao.countTransactions(id)
        accountDao.detachTransferCounterparts(id, account.name)
        accountDao.clearDebtPaymentLinks(id)
        accountDao.clearObligationPaymentLinks(id)
        accountDao.clearChatLinks(id)
        accountDao.clearObligationAccount(id)
        accountDao.deleteTransactionsOf(id)
        accountDao.deleteTransfersOf(id)
        accountDao.deleteById(id)
        audit("account", id, "delete", "${account.name}, $count movimientos", clock.millis())
    }

    /**
     * Conciliación: si el saldo real que ves en el banco no coincide con el calculado,
     * crea un movimiento de AJUSTE por la diferencia. El ajuste no cuenta como ingreso
     * ni gasto. Devuelve la diferencia aplicada (cero si ya cuadraba).
     */
    suspend fun reconcile(accountId: Long, realBalance: Money): Money = db.withTransaction {
        val calculated = Money(requireNotNull(accountDao.balanceOf(accountId)) { "La cuenta no existe" })
        val difference = realBalance - calculated
        if (!difference.isZero) {
            val now = clock.millis()
            val txId = transactionDao.insert(
                TransactionEntity(
                    accountId = accountId,
                    amountCentavos = difference.centavos,
                    kind = TransactionKind.ADJUSTMENT,
                    categoryId = null,
                    description = "Ajuste por conciliación",
                    occurredAtEpochMs = now,
                    origin = TransactionOrigin.ADJUSTMENT,
                    notes = "Saldo calculado ${calculated.formatCop()}, saldo real ${realBalance.formatCop()}",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            audit("account", accountId, "reconcile", "Ajuste ${difference.formatCop()} (movimiento $txId)", now)
        }
        difference
    }

    private suspend fun audit(type: String, id: Long, action: String, detail: String?, now: Long) {
        transactionDao.insertAudit(AuditLogEntity(entityType = type, entityId = id, action = action, detail = detail, createdAtEpochMs = now))
    }
}
