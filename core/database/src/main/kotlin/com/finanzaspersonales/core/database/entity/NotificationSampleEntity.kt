package com.finanzaspersonales.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Muestra de una notificación bancaria, solo si activas el modo de muestras.
 * El texto se guarda con los números de cuenta, tarjeta y teléfono tapados,
 * dentro de la base cifrada, y se borra solo a los 7 días.
 */
@Entity(tableName = "notification_samples")
data class NotificationSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String,
    @ColumnInfo(name = "masked_text") val maskedText: String,
    /** Qué entendió la app ("Compra $45.900" o "No reconocida"). */
    val result: String,
    @ColumnInfo(name = "posted_at") val postedAtEpochMs: Long,
)
