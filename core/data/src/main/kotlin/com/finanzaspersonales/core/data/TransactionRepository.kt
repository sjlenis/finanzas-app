package com.finanzaspersonales.core.data

import androidx.room.withTransaction
import com.finanzaspersonales.core.common.ClosedOpenRange
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.database.entity.AuditLogEntity
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.model.CategorySpending
import com.finanzaspersonales.core.model.ManualEntryType
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.Transaction
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.formatCop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Datos de un movimiento para abrirlo en el formulario de edición. */
sealed interface EditableEntry {
    data class Manual(
        val id: Long,
        val type: ManualEntryType,
        val amount: Money,
        val accountId: Long,
        val categoryId: Long?,
        val description: String,
        val occurredAt: Instant,
        val notes: String?,
    ) : EditableEntry

    data class Transfer(
        val transferId: Long,
        val fromAccountId: Long,
        val toAccountId: Long,
        val amount: Money,
        val description: String,
        val occurredAt: Instant,
    ) : EditableEntry

    /** Ajustes y otros tipos que no se editan desde el formulario. */
    data class ReadOnly(val reason: String) : EditableEntry
}

@Singleton
class TransactionRepository @Inject constructor(
    private val db: FinanzasDatabase,
    private val clock: Clock,
) {
    private val dao get() = db.transactionDao()

    fun observe(range: ClosedOpenRange, accountId: Long? = null, categoryId: Long? = null): Flow<List<Transaction>> =
        dao.observeFiltered(range.start.toEpochMilli(), range.endExclusive.toEpochMilli(), accountId, categoryId)
            .map { rows -> rows.map { it.toModel() } }

    fun observeSpendingByCategory(range: ClosedOpenRange): Flow<List<CategorySpending>> =
        dao.observeSpendingByCategory(range.start.toEpochMilli(), range.endExclusive.toEpochMilli()).map { rows ->
            rows.map { CategorySpending(it.categoryId, it.categoryName ?: "Sin categoría", Money(it.amountCentavos)) }
        }

    suspend fun getEditable(transactionId: Long): EditableEntry? {
        val tx = dao.getById(transactionId) ?: return null
        tx.transferId?.let { transferId ->
            val transfer = dao.getTransfer(transferId) ?: return null
            return EditableEntry.Transfer(
                transferId = transferId,
                fromAccountId = transfer.fromAccountId,
                toAccountId = transfer.toAccountId,
                amount = Money(transfer.amountCentavos),
                description = tx.description,
                occurredAt = Instant.ofEpochMilli(transfer.occurredAtEpochMs),
            )
        }
        val type = ManualEntryType.fromKind(tx.kind)
            ?: return EditableEntry.ReadOnly(
                if (tx.kind == TransactionKind.ADJUSTMENT) "Los ajustes de conciliación no se editan; puedes eliminarlo y conciliar de nuevo."
                else "Este tipo de movimiento se edita desde su propio módulo.",
            )
        return EditableEntry.Manual(
            id = tx.id,
            type = type,
            amount = Money(tx.amountCentavos).abs(),
            accountId = tx.accountId,
            categoryId = tx.categoryId,
            description = tx.description,
            occurredAt = Instant.ofEpochMilli(tx.occurredAtEpochMs),
            notes = tx.notes,
        )
    }

    /** Crea ([id] null) o actualiza un ingreso, gasto, devolución o comisión. */
    suspend fun saveManual(
        id: Long?,
        type: ManualEntryType,
        amount: Money,
        accountId: Long,
        categoryId: Long?,
        description: String,
        occurredAt: Instant,
        notes: String?,
    ): Long = db.withTransaction {
        val signed = type.signedAmount(amount)
        val now = clock.millis()
        val cleanDescription = description.trim().ifEmpty { type.label }
        val cleanNotes = notes?.trim()?.ifEmpty { null }
        if (id == null) {
            val newId = dao.insert(
                TransactionEntity(
                    accountId = accountId,
                    amountCentavos = signed.centavos,
                    kind = type.kind,
                    categoryId = categoryId,
                    description = cleanDescription,
                    occurredAtEpochMs = occurredAt.toEpochMilli(),
                    origin = TransactionOrigin.MANUAL,
                    notes = cleanNotes,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            audit(newId, "create", "${type.label} ${signed.formatCop()}", now)
            newId
        } else {
            val current = requireNotNull(dao.getById(id)) { "El movimiento no existe" }
            require(current.transferId == null) { "Una transferencia se edita como transferencia" }
            dao.update(
                current.copy(
                    accountId = accountId,
                    amountCentavos = signed.centavos,
                    kind = type.kind,
                    categoryId = categoryId,
                    description = cleanDescription,
                    occurredAtEpochMs = occurredAt.toEpochMilli(),
                    notes = cleanNotes,
                    updatedAtEpochMs = now,
                ),
            )
            audit(id, "update", "${Money(current.amountCentavos).formatCop()} -> ${signed.formatCop()}", now)
            id
        }
    }

    /**
     * Crea o reemplaza una transferencia entre cuentas propias (también sirve para
     * retiros de cajero: banco -> efectivo). Editar borra y recrea ambos lados juntos.
     */
    suspend fun saveTransfer(
        transferId: Long?,
        fromAccountId: Long,
        toAccountId: Long,
        amount: Money,
        description: String,
        occurredAt: Instant,
    ): Long = db.withTransaction {
        if (transferId != null) dao.deleteTransferById(transferId)
        dao.createTransfer(
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amountCentavos = amount.centavos,
            occurredAtEpochMs = occurredAt.toEpochMilli(),
            description = description.trim().ifEmpty { "Transferencia" },
            nowEpochMs = clock.millis(),
        )
    }

    /** Elimina un movimiento. Si es parte de una transferencia, elimina ambos lados. */
    suspend fun delete(transactionId: Long) = db.withTransaction {
        val tx = dao.getById(transactionId) ?: return@withTransaction
        val now = clock.millis()
        val transferId = tx.transferId
        if (transferId != null) {
            dao.deleteTransferById(transferId) // ON DELETE CASCADE borra los dos movimientos
            audit(transferId, "delete_transfer", Money(tx.amountCentavos).abs().formatCop(), now, type = "transfer")
        } else {
            dao.deleteById(transactionId)
            audit(transactionId, "delete", "${tx.kind} ${Money(tx.amountCentavos).formatCop()} ${tx.description}", now)
        }
    }

    private suspend fun audit(id: Long, action: String, detail: String?, now: Long, type: String = "transaction") {
        dao.insertAudit(AuditLogEntity(entityType = type, entityId = id, action = action, detail = detail, createdAtEpochMs = now))
    }
}
