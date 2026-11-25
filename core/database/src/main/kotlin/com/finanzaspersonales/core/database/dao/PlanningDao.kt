package com.finanzaspersonales.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.finanzaspersonales.core.database.entity.BudgetEntity
import com.finanzaspersonales.core.database.entity.DebtEntity
import com.finanzaspersonales.core.database.entity.DebtPaymentEntity
import com.finanzaspersonales.core.database.entity.GoalContributionEntity
import com.finanzaspersonales.core.database.entity.ObligationEntity
import com.finanzaspersonales.core.database.entity.ObligationPaymentEntity
import com.finanzaspersonales.core.database.entity.SavingsGoalEntity
import kotlinx.coroutines.flow.Flow

data class GoalWithSaved(
    @Embedded val goal: SavingsGoalEntity,
    @ColumnInfo(name = "saved_centavos") val savedCentavos: Long,
)

data class BudgetWithCategory(
    @Embedded val budget: BudgetEntity,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "is_essential") val isEssential: Boolean,
)

/** Gasto neto de una categoría principal en un mes (subcategorías incluidas). */
data class MonthlyCategorySpending(
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val month: String,
    @ColumnInfo(name = "amount_centavos") val amountCentavos: Long,
)

/** Totales por mes ("2026-11") en hora de Colombia. */
data class MonthlyTotals(
    val month: String,
    @ColumnInfo(name = "income_centavos") val incomeCentavos: Long,
    @ColumnInfo(name = "expense_centavos") val expenseCentavos: Long,
    @ColumnInfo(name = "essential_centavos") val essentialCentavos: Long,
)

@Dao
interface PlanningDao {

    // ---------------------------------------------------------------- deudas
    @Insert suspend fun insertDebt(debt: DebtEntity): Long
    @Update suspend fun updateDebt(debt: DebtEntity)
    @Query("DELETE FROM debts WHERE id = :id") suspend fun deleteDebt(id: Long)
    @Query("SELECT * FROM debts WHERE id = :id") suspend fun getDebt(id: Long): DebtEntity?
    @Query("SELECT * FROM debts ORDER BY status = 'PAID', balance_centavos DESC") fun observeDebts(): Flow<List<DebtEntity>>
    @Query("SELECT * FROM debts WHERE id = :id") fun observeDebt(id: Long): Flow<DebtEntity?>
    @Insert suspend fun insertDebtPayment(payment: DebtPaymentEntity): Long
    @Query("SELECT * FROM debt_payments WHERE debt_id = :debtId ORDER BY paid_at DESC")
    fun observeDebtPayments(debtId: Long): Flow<List<DebtPaymentEntity>>
    @Query("SELECT * FROM debt_payments WHERE paid_at >= :fromEpochMs AND paid_at < :toEpochMs")
    fun observeDebtPaymentsBetween(fromEpochMs: Long, toEpochMs: Long): Flow<List<DebtPaymentEntity>>

    // ---------------------------------------------------------------- metas
    @Insert suspend fun insertGoal(goal: SavingsGoalEntity): Long
    @Update suspend fun updateGoal(goal: SavingsGoalEntity)
    @Query("DELETE FROM savings_goals WHERE id = :id") suspend fun deleteGoal(id: Long)
    @Query("SELECT * FROM savings_goals WHERE id = :id") suspend fun getGoal(id: Long): SavingsGoalEntity?
    @Query(
        """
        SELECT g.*, COALESCE((SELECT SUM(c.amount_centavos) FROM goal_contributions c WHERE c.goal_id = g.id), 0) AS saved_centavos
        FROM savings_goals g ORDER BY g.is_emergency_fund DESC, g.status = 'ACHIEVED', g.created_at
        """,
    )
    fun observeGoals(): Flow<List<GoalWithSaved>>
    @Query("SELECT * FROM savings_goals WHERE is_emergency_fund = 1 LIMIT 1") suspend fun getEmergencyFund(): SavingsGoalEntity?
    @Query("SELECT COALESCE(SUM(amount_centavos), 0) FROM goal_contributions WHERE goal_id = :goalId")
    suspend fun savedIn(goalId: Long): Long
    @Insert suspend fun insertContribution(contribution: GoalContributionEntity): Long
    @Query("SELECT * FROM goal_contributions WHERE goal_id = :goalId ORDER BY created_at DESC")
    fun observeContributions(goalId: Long): Flow<List<GoalContributionEntity>>

    // ---------------------------------------------------------------- presupuestos
    @Query(
        """
        SELECT b.*, c.name AS category_name, c.is_essential AS is_essential
        FROM budgets b JOIN categories c ON c.id = b.category_id ORDER BY c.name
        """,
    )
    fun observeBudgets(): Flow<List<BudgetWithCategory>>
    @Query("SELECT * FROM budgets WHERE category_id = :categoryId") suspend fun getBudgetFor(categoryId: Long): BudgetEntity?
    @Insert suspend fun insertBudget(budget: BudgetEntity): Long
    @Update suspend fun updateBudget(budget: BudgetEntity)
    @Query("DELETE FROM budgets WHERE id = :id") suspend fun deleteBudget(id: Long)

    // ---------------------------------------------------------------- obligaciones
    @Insert suspend fun insertObligation(obligation: ObligationEntity): Long
    @Update suspend fun updateObligation(obligation: ObligationEntity)
    @Query("DELETE FROM obligations WHERE id = :id") suspend fun deleteObligation(id: Long)
    @Query("SELECT * FROM obligations WHERE id = :id") suspend fun getObligation(id: Long): ObligationEntity?
    @Query("SELECT * FROM obligations ORDER BY active DESC, day_of_month, name") fun observeObligations(): Flow<List<ObligationEntity>>
    @Query("SELECT * FROM obligation_payments WHERE period = :period") fun observeObligationPayments(period: String): Flow<List<ObligationPaymentEntity>>
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun insertObligationPayment(payment: ObligationPaymentEntity): Long
    @Query("DELETE FROM obligation_payments WHERE obligation_id = :obligationId AND period = :period")
    suspend fun deleteObligationPayment(obligationId: Long, period: String)

    // ---------------------------------------------------------------- historial para cálculos
    /**
     * Ingresos, gastos y gastos esenciales por mes. [offsetSeconds] desplaza a hora de
     * Colombia (-18000) para agrupar por el mes local.
     */
    @Query(
        """
        SELECT strftime('%Y-%m', t.occurred_at / 1000 + :offsetSeconds, 'unixepoch') AS month,
          COALESCE(SUM(CASE WHEN t.kind = 'INCOME' THEN t.amount_centavos ELSE 0 END), 0) AS income_centavos,
          COALESCE(SUM(CASE WHEN t.kind IN ('EXPENSE', 'FEE', 'REFUND') THEN -t.amount_centavos ELSE 0 END), 0) AS expense_centavos,
          COALESCE(SUM(CASE WHEN t.kind IN ('EXPENSE', 'FEE', 'REFUND') AND COALESCE(p.is_essential, c.is_essential, 0) = 1
                            THEN -t.amount_centavos ELSE 0 END), 0) AS essential_centavos
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.category_id
        LEFT JOIN categories p ON p.id = c.parent_id
        WHERE t.status = 'CONFIRMED' AND t.occurred_at >= :fromEpochMs AND t.occurred_at < :toEpochMs
        GROUP BY month ORDER BY month
        """,
    )
    fun observeMonthlyTotals(fromEpochMs: Long, toEpochMs: Long, offsetSeconds: Long): Flow<List<MonthlyTotals>>

    @Query(
        """
        SELECT COALESCE(p.id, c.id) AS category_id,
          strftime('%Y-%m', t.occurred_at / 1000 + :offsetSeconds, 'unixepoch') AS month,
          SUM(-t.amount_centavos) AS amount_centavos
        FROM transactions t
        JOIN categories c ON c.id = t.category_id
        LEFT JOIN categories p ON p.id = c.parent_id
        WHERE t.status = 'CONFIRMED' AND t.kind IN ('EXPENSE', 'FEE', 'REFUND')
          AND t.occurred_at >= :fromEpochMs AND t.occurred_at < :toEpochMs
        GROUP BY COALESCE(p.id, c.id), month
        """,
    )
    fun observeMonthlyCategorySpending(fromEpochMs: Long, toEpochMs: Long, offsetSeconds: Long): Flow<List<MonthlyCategorySpending>>
}
