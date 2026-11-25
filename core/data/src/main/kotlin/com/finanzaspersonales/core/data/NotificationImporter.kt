package com.finanzaspersonales.core.data

import androidx.room.withTransaction
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.database.entity.AuditLogEntity
import com.finanzaspersonales.core.database.entity.NotificationSampleEntity
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.TransactionStatus
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.engine.notifications.Bank
import com.finanzaspersonales.engine.notifications.BankNotificationParser
import com.finanzaspersonales.engine.notifications.Confidence
import com.finanzaspersonales.engine.notifications.DetectedKind
import com.finanzaspersonales.engine.notifications.DetectedMovement
import com.finanzaspersonales.engine.notifications.IgnoreReason
import com.finanzaspersonales.engine.notifications.NotificationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Qué pasó con una notificación (útil para pruebas y para la pantalla de muestras). */
enum class ImportOutcome { DISABLED, IGNORED, NO_ACCOUNT, ALREADY_IMPORTED, PENDING_CREATED }

data class NotificationSample(val id: Long, val bank: String, val maskedText: String, val result: String, val postedAt: Instant)

/**
 * Convierte notificaciones de Nequi y Bancolombia en movimientos PENDIENTES.
 * Nunca guarda el texto completo de la notificación: solo valor, tipo, comercio o
 * persona, y una huella (hash) para no registrar dos veces el mismo aviso.
 */
@Singleton
class NotificationImporter @Inject constructor(
    private val db: FinanzasDatabase,
    private val preferences: UserPreferences,
    private val clock: Clock,
) {
    suspend fun process(packageName: String, title: String?, text: String, postedAt: Instant): ImportOutcome {
        if (!preferences.notificationDetectionEnabled.first()) return ImportOutcome.DISABLED
        val result = BankNotificationParser.parse(packageName, title, text)
        saveSampleIfEnabled(result, title, text, postedAt)
        val movement = (result as? NotificationResult.Detected)?.movement ?: return ImportOutcome.IGNORED
        return createPending(movement, fingerprint(packageName, title, text, postedAt), postedAt)
    }

    private suspend fun createPending(m: DetectedMovement, fingerprint: String, postedAt: Instant): ImportOutcome {
        // La cuenta se busca fuera de la transacción (las consultas con Flow no deben ir dentro).
        val account = db.accountDao().observeActiveWithBalance().first().map { it.account }.let { accounts ->
            accounts.firstOrNull { it.institution.equals(m.bank.displayName, ignoreCase = true) }
                ?: accounts.firstOrNull { it.name.equals(m.bank.displayName, ignoreCase = true) }
        } ?: return ImportOutcome.NO_ACCOUNT
        return db.withTransaction {
            val signed = if (m.kind.inflow) m.amount else -m.amount
            val window = Duration.ofMinutes(10).toMillis()
            val similar = db.transactionDao().countSimilar(
                account.id, signed.centavos, postedAt.toEpochMilli() - window, postedAt.toEpochMilli() + window,
            )
            val notes = buildList {
                add("Detectado en una notificación de ${m.bank.displayName} (${m.kind.label.lowercase()}).")
                if (m.confidence == Confidence.LOW) add("Confianza baja: revisa que sea correcto.")
                if (similar > 0) add("Posible duplicado: hay otro movimiento de ${signed.abs().formatCop()} en esta cuenta cerca de esta hora.")
            }.joinToString(" ")
            val now = clock.millis()
            val id = db.transactionDao().insertIfNew(
                TransactionEntity(
                    accountId = account.id,
                    amountCentavos = signed.centavos,
                    kind = m.kind.toTransactionKind(),
                    categoryId = null,
                    description = m.description,
                    counterparty = m.counterparty,
                    occurredAtEpochMs = postedAt.toEpochMilli(),
                    origin = TransactionOrigin.NOTIFICATION,
                    status = TransactionStatus.PENDING,
                    fingerprint = fingerprint,
                    notes = notes,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            if (id == -1L) return@withTransaction ImportOutcome.ALREADY_IMPORTED
            db.transactionDao().insertAudit(
                AuditLogEntity(entityType = "transaction", entityId = id, action = "detected", detail = "${m.bank} ${signed.formatCop()}", createdAtEpochMs = now),
            )
            ImportOutcome.PENDING_CREATED
        }
    }

    private suspend fun saveSampleIfEnabled(result: NotificationResult, title: String?, text: String, postedAt: Instant) {
        if (!preferences.notificationSamplesEnabled.first()) return
        val bank: Bank = when (result) {
            is NotificationResult.Detected -> result.movement.bank
            is NotificationResult.Ignored -> {
                // Ni otras apps ni códigos de seguridad se guardan nunca.
                if (result.reason == IgnoreReason.NOT_BANK || result.reason == IgnoreReason.SECURITY) return
                result.bank ?: return
            }
        }
        val summary = when (result) {
            is NotificationResult.Detected -> "${result.movement.kind.label} ${result.movement.amount.formatCop()}"
            is NotificationResult.Ignored -> when (result.reason) {
                IgnoreReason.PROMOTION -> "Ignorada: promoción"
                IgnoreReason.NO_AMOUNT -> "No reconocida: sin valor"
                else -> "No reconocida"
            }
        }
        val dao = db.notificationSampleDao()
        dao.deleteOlderThan(clock.millis() - SAMPLE_RETENTION.toMillis())
        dao.insert(
            NotificationSampleEntity(
                bank = bank.displayName,
                maskedText = BankNotificationParser.mask(listOfNotNull(title, text).joinToString(" | ")).take(500),
                result = summary,
                postedAtEpochMs = postedAt.toEpochMilli(),
            ),
        )
    }

    fun observeSamples(): Flow<List<NotificationSample>> = db.notificationSampleDao().observeAll().map { list ->
        list.map { NotificationSample(it.id, it.bank, it.maskedText, it.result, Instant.ofEpochMilli(it.postedAtEpochMs)) }
    }

    suspend fun clearSamples() = db.notificationSampleDao().deleteAll()

    suspend fun purgeOldSamples() = db.notificationSampleDao().deleteOlderThan(clock.millis() - SAMPLE_RETENTION.toMillis())

    private fun DetectedKind.toTransactionKind() = when (this) {
        DetectedKind.PURCHASE, DetectedKind.PAYMENT, DetectedKind.TRANSFER_OUT -> TransactionKind.EXPENSE
        DetectedKind.TRANSFER_IN -> TransactionKind.INCOME
        DetectedKind.WITHDRAWAL -> TransactionKind.WITHDRAWAL
        DetectedKind.FEE -> TransactionKind.FEE
    }

    companion object {
        val SAMPLE_RETENTION: Duration = Duration.ofDays(7)

        /** Huella del aviso: el mismo aviso repetido (o actualizado) no crea otro movimiento. */
        fun fingerprint(packageName: String, title: String?, text: String, postedAt: Instant): String {
            val minute = postedAt.epochSecond / 60
            val raw = "$packageName|${title.orEmpty().trim()}|${text.trim()}|$minute"
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
