package com.finanzaspersonales.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Deuda con una entidad o persona. [balanceCentavos] es el saldo pendiente actual;
 * cada pago lo reduce y queda registrado en [DebtPaymentEntity].
 * La tasa va en centésimas de punto (25,5 % = 2550) y [rateType] dice si es E.A. o mensual.
 */
@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val creditor: String?,
    @ColumnInfo(name = "initial_centavos") val initialCentavos: Long,
    @ColumnInfo(name = "balance_centavos") val balanceCentavos: Long,
    @ColumnInfo(name = "rate_bp") val rateBasisPoints: Int,
    @ColumnInfo(name = "rate_type") val rateType: String,
    @ColumnInfo(name = "monthly_payment_centavos") val monthlyPaymentCentavos: Long,
    /** Día del mes en que vence la cuota (1-31), si aplica. */
    @ColumnInfo(name = "due_day") val dueDay: Int?,
    @ColumnInfo(name = "remaining_installments") val remainingInstallments: Int?,
    /** ACTIVE o PAID. */
    val status: String,
    val notes: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "debt_payments",
    foreignKeys = [ForeignKey(entity = DebtEntity::class, parentColumns = ["id"], childColumns = ["debt_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("debt_id")],
)
data class DebtPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "debt_id") val debtId: Long,
    /** Movimiento de la cuenta desde la que se pagó (si se registró). */
    @ColumnInfo(name = "transaction_id") val transactionId: Long?,
    @ColumnInfo(name = "amount_centavos") val amountCentavos: Long,
    @ColumnInfo(name = "balance_after_centavos") val balanceAfterCentavos: Long,
    @ColumnInfo(name = "paid_at") val paidAtEpochMs: Long,
)

/**
 * Meta de ahorro o fondo de emergencia. El dinero sigue en tus cuentas: la meta es
 * un "sobre" que lo reserva, así no se cuenta como dinero disponible para gastar.
 */
@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "target_centavos") val targetCentavos: Long,
    /** Fecha límite como día epoch (LocalDate.toEpochDay), si tiene. */
    @ColumnInfo(name = "deadline_epoch_day") val deadlineEpochDay: Long?,
    @ColumnInfo(name = "is_emergency_fund") val isEmergencyFund: Boolean,
    /** Solo fondo de emergencia: meses de gastos esenciales (3 a 6). */
    @ColumnInfo(name = "target_months") val targetMonths: Int?,
    /** Si es true, la meta del fondo la fijó el usuario y no se recalcula. */
    @ColumnInfo(name = "custom_target") val customTarget: Boolean,
    @ColumnInfo(name = "planned_monthly_centavos") val plannedMonthlyCentavos: Long?,
    /** ACTIVE, ACHIEVED o PAUSED. */
    val status: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "goal_contributions",
    foreignKeys = [ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["goal_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("goal_id")],
)
data class GoalContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "goal_id") val goalId: Long,
    /** Positivo = aporte, negativo = retiro. */
    @ColumnInfo(name = "amount_centavos") val amountCentavos: Long,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
)

/** Presupuesto mensual de una categoría principal. */
@Entity(
    tableName = "budgets",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["category_id"], unique = true)],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "limit_centavos") val limitCentavos: Long,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
)

/**
 * Pago o ingreso que se repite cada mes (arriendo, servicios, suscripciones, salario).
 * Alimenta el calendario y el dinero comprometido.
 */
@Entity(
    tableName = "obligations",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("category_id")],
)
data class ObligationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "amount_centavos") val amountCentavos: Long,
    @ColumnInfo(name = "day_of_month") val dayOfMonth: Int,
    @ColumnInfo(name = "is_income") val isIncome: Boolean,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "account_id") val accountId: Long?,
    val active: Boolean,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
)

/** Marca que una obligación ya se pagó (o el ingreso ya llegó) en un mes ("2026-11"). */
@Entity(
    tableName = "obligation_payments",
    foreignKeys = [ForeignKey(entity = ObligationEntity::class, parentColumns = ["id"], childColumns = ["obligation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["obligation_id", "period"], unique = true)],
)
data class ObligationPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "obligation_id") val obligationId: Long,
    val period: String,
    @ColumnInfo(name = "transaction_id") val transactionId: Long?,
    @ColumnInfo(name = "paid_at") val paidAtEpochMs: Long,
)
