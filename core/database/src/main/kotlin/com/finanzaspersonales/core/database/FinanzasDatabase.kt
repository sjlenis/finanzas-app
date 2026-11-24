package com.finanzaspersonales.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.finanzaspersonales.core.database.dao.AccountDao
import com.finanzaspersonales.core.database.dao.CategoryDao
import com.finanzaspersonales.core.database.dao.ChatDao
import com.finanzaspersonales.core.database.dao.TransactionDao
import com.finanzaspersonales.core.database.entity.AccountEntity
import com.finanzaspersonales.core.database.entity.AuditLogEntity
import com.finanzaspersonales.core.database.entity.CategoryEntity
import com.finanzaspersonales.core.database.entity.ChatMessageEntity
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.database.entity.TransferEntity

/**
 * Versión 1: cuentas, categorías, movimientos, transferencias y registro de cambios.
 * Versión 2 (Fase 4): historial del chat del asistente.
 * Presupuestos, deudas, metas, obligaciones e importaciones llegan con migraciones
 * en sus fases, para no crear tablas que todavía no usa ninguna pantalla.
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        TransferEntity::class,
        AuditLogEntity::class,
        ChatMessageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FinanzasDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun chatDao(): ChatDao

    companion object {
        const val FILE_NAME = "finanzas.db"
    }
}
