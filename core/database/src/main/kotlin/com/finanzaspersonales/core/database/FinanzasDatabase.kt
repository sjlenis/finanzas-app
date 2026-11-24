package com.finanzaspersonales.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.finanzaspersonales.core.database.dao.AccountDao
import com.finanzaspersonales.core.database.dao.CategoryDao
import com.finanzaspersonales.core.database.dao.TransactionDao
import com.finanzaspersonales.core.database.entity.AccountEntity
import com.finanzaspersonales.core.database.entity.AuditLogEntity
import com.finanzaspersonales.core.database.entity.CategoryEntity
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.database.entity.TransferEntity

/**
 * Versión 1 del esquema: cuentas, categorías, movimientos, transferencias y registro de cambios.
 * Presupuestos, deudas, metas, obligaciones, chat e importaciones llegan con migraciones
 * en sus fases, para no crear tablas que todavía no usa ninguna pantalla.
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        TransferEntity::class,
        AuditLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FinanzasDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        const val FILE_NAME = "finanzas.db"
    }
}
