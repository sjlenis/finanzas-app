package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.database.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class ChatRole { USER, ASSISTANT }

/**
 * Mensaje guardado del chat. [cardJson] es el borrador de la tarjeta de confirmación
 * en el formato que define el módulo del asistente; aquí solo se guarda.
 */
data class StoredChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val cardJson: String?,
    val cardStatus: String?,
    val transactionId: Long?,
    val createdAt: Instant,
)

@Singleton
class ChatRepository @Inject constructor(
    private val db: FinanzasDatabase,
    private val clock: Clock,
) {
    private val dao get() = db.chatDao()

    fun observe(limit: Int = HISTORY_LIMIT): Flow<List<StoredChatMessage>> =
        dao.observeLatest(limit).map { list -> list.map { it.toModel() } }

    suspend fun add(role: ChatRole, text: String, cardJson: String? = null, cardStatus: String? = null): Long =
        dao.insert(
            ChatMessageEntity(
                role = role.name.lowercase(),
                text = text,
                draftJson = cardJson,
                cardStatus = cardStatus,
                createdAtEpochMs = clock.millis(),
            ),
        )

    suspend fun get(id: Long): StoredChatMessage? = dao.getById(id)?.toModel()

    /** Cambia el borrador o el estado de una tarjeta (por ejemplo, al editarla o guardarla). */
    suspend fun updateCard(id: Long, cardJson: String?, cardStatus: String?, transactionId: Long? = null) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(draftJson = cardJson, cardStatus = cardStatus, transactionId = transactionId ?: current.transactionId))
    }

    /** Borra el historial del chat. Los movimientos ya guardados no se tocan. */
    suspend fun clear() = dao.deleteAll()

    private fun ChatMessageEntity.toModel() = StoredChatMessage(
        id = id,
        role = if (role == "user") ChatRole.USER else ChatRole.ASSISTANT,
        text = text,
        cardJson = draftJson,
        cardStatus = cardStatus,
        transactionId = transactionId,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    )

    companion object {
        const val HISTORY_LIMIT = 200
    }
}
