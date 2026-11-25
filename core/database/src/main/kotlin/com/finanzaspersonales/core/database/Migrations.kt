package com.finanzaspersonales.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones escritas a mano: nunca se borra la base del usuario para cambiar el
 * esquema. Room comprueba al abrir que el resultado coincide con las entidades.
 */
object Migrations {

    /** Fase 4: historial del chat del asistente. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`draft_json` TEXT, " +
                    "`card_status` TEXT, " +
                    "`transaction_id` INTEGER, " +
                    "`created_at` INTEGER NOT NULL)",
            )
        }
    }

    /** Fase 5: muestras opcionales de notificaciones (texto ya enmascarado). */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `notification_samples` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`bank` TEXT NOT NULL, " +
                    "`masked_text` TEXT NOT NULL, " +
                    "`result` TEXT NOT NULL, " +
                    "`posted_at` INTEGER NOT NULL)",
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
