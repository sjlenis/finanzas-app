package com.finanzaspersonales.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.finanzaspersonales.core.database.dao.AccountDao
import com.finanzaspersonales.core.database.dao.CategoryDao
import com.finanzaspersonales.core.database.dao.ChatDao
import com.finanzaspersonales.core.database.dao.NotificationSampleDao
import com.finanzaspersonales.core.database.dao.PlanningDao
import com.finanzaspersonales.core.database.dao.TransactionDao
import com.finanzaspersonales.core.database.entity.AccountEntity
import com.finanzaspersonales.core.database.entity.AuditLogEntity
import com.finanzaspersonales.core.database.entity.CategoryEntity
import com.finanzaspersonales.core.database.entity.ChatMessageEntity
import com.finanzaspersonales.core.database.entity.NotificationSampleEntity
import com.finanzaspersonales.core.database.entity.BudgetEntity
import com.finanzaspersonales.core.database.entity.DebtEntity
import com.finanzaspersonales.core.database.entity.DebtPaymentEntity
import com.finanzaspersonales.core.database.entity.GoalContributionEntity
import com.finanzaspersonales.core.database.entity.ObligationEntity
import com.finanzaspersonales.core.database.entity.ObligationPaymentEntity
import com.finanzaspersonales.core.database.entity.SavingsGoalEntity
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.database.entity.TransferEntity

/**
 * Versión 1: cuentas, categorías, movimientos, transferencias y registro de cambios.
 * Versión 2 (Fase 4): historial del chat del asistente.
 * Versión 3 (Fase 5): muestras opcionales de notificaciones bancarias.
 * Versión 4 (Fase 6): deudas, metas y fondo de emergencia, presupuestos y obligaciones.
 *   Se agrega con una migración automática de Room (solo crea tablas nuevas).
 * Versión 5: cuotas quincenales en deudas, pagos fijos en dólares y valor real pagado
 *   (migración automática: solo agrega columnas con valor por defecto).
 * La importación de extractos llegará con su propia migración, para no crear tablas
 * que todavía no usa ninguna pantalla.
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        TransferEntity::class,
        AuditLogEntity::class,
        ChatMessageEntity::class,
        NotificationSampleEntity::class,
        DebtEntity::class,
        DebtPaymentEntity::class,
        SavingsGoalEntity::class,
        GoalContributionEntity::class,
        BudgetEntity::class,
        ObligationEntity::class,
        ObligationPaymentEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
@TypeConverters(Converters::class)
abstract class FinanzasDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun chatDao(): ChatDao
    abstract fun notificationSampleDao(): NotificationSampleDao
    abstract fun planningDao(): PlanningDao

    companion object {
        const val FILE_NAME = "finanzas.db"
    }
}
