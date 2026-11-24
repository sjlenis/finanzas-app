package com.finanzaspersonales.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Mensaje del chat del asistente. Se guarda en la base cifrada, igual que los
 * movimientos. Si el mensaje trae una tarjeta de confirmación, [draftJson] guarda
 * el borrador y [cardStatus] su estado (pendiente, guardado o cancelado).
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "user" o "assistant". */
    val role: String,
    val text: String,
    @ColumnInfo(name = "draft_json") val draftJson: String? = null,
    @ColumnInfo(name = "card_status") val cardStatus: String? = null,
    @ColumnInfo(name = "transaction_id") val transactionId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
)
