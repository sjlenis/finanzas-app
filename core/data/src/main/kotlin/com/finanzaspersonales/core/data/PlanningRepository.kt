package com.finanzaspersonales.core.data

import androidx.room.withTransaction
import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.common.instantForDate
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.database.entity.AuditLogEntity
import com.finanzaspersonales.core.database.entity.BudgetEntity
import com.finanzaspersonales.core.database.entity.DebtEntity
import com.finanzaspersonales.core.database.entity.DebtPaymentEntity
import com.finanzaspersonales.core.database.entity.GoalContributionEntity
import com.finanzaspersonales.core.database.entity.ObligationEntity
import com.finanzaspersonales.core.database.entity.ObligationPaymentEntity
import com.finanzaspersonales.core.database.entity.SavingsGoalEntity
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.formatCop
import com.finanzaspersonales.core.model.sum
import com.finanzaspersonales.engine.finance.CategoryHistory
import com.finanzaspersonales.engine.finance.RateType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Deudas, metas, fondo de emergencia, presupuestos y obligaciones. */
@Singleton
class PlanningRepository @Inject constructor(
    private val db: FinanzasDatabase,
    private val clock: Clock,
) {
    private val dao get() = db.planningDao()

    // ================================================================= deudas

    fun observeDebts(): Flow<List<Debt>> = dao.observeDebts().map { list -> list.map { it.toModel() } }

    fun observeDebt(id: Long): Flow<Debt?> = dao.observeDebt(id).map { it?.toModel() }

    fun observeDebtPayments(debtId: Long): Flow<List<DebtPayment>> = dao.observeDebtPayments(debtId).map { list ->
        list.map { DebtPayment(it.id, Money(it.amountCentavos), Money(it.balanceAfterCentavos), Instant.ofEpochMilli(it.paidAtEpochMs), it.transactionId) }
    }

    suspend fun saveDebt(
        id: Long?,
        name: String,
        creditor: String?,
        initial: Money,
        balance: Money,
        rateBasisPoints: Int,
        rateType: RateType,
        installment: Money,
        dueDay: Int?,
        remainingInstallments: Int?,
        notes: String?,
        frequency: PaymentFrequency = PaymentFrequency.MONTHLY,
    ): Long = db.withTransaction {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Escribe un nombre" }
        require(initial.centavos > 0) { "El monto total debe ser mayor que cero" }
        require(!balance.isNegative) { "El saldo no puede ser negativo" }
        require(installment.centavos > 0 || balance.isZero) { "Escribe el valor de la cuota" }
        require(rateBasisPoints in 0..100_000) { "Revisa la tasa de interés" }
        require(dueDay == null || dueDay in 1..31) { "El día de pago debe estar entre 1 y 31" }
        val now = clock.millis()
        val status = if (balance.isZero) DebtStatus.PAID else DebtStatus.ACTIVE
        val entity = DebtEntity(
            id = id ?: 0,
            name = clean,
            creditor = creditor?.trim()?.ifEmpty { null },
            initialCentavos = initial.centavos,
            balanceCentavos = balance.centavos,
            rateBasisPoints = rateBasisPoints,
            rateType = rateType.name,
            monthlyPaymentCentavos = installment.centavos,
            paymentFrequency = frequency.name,
            dueDay = dueDay,
            remainingInstallments = remainingInstallments,
            status = status.name,
            notes = notes?.trim()?.ifEmpty { null },
            createdAtEpochMs = id?.let { dao.getDebt(it)?.createdAtEpochMs } ?: now,
        )
        val savedId = if (id == null) dao.insertDebt(entity) else { dao.updateDebt(entity); id }
        audit("debt", savedId, if (id == null) "create" else "update", "Saldo ${balance.formatCop()}", now)
        savedId
    }

    suspend fun deleteDebt(id: Long) = db.withTransaction {
        dao.deleteDebt(id)
        audit("debt", id, "delete", null, clock.millis())
    }

    /**
     * Registra un pago. Si se indica [accountId], también sale el dinero de esa cuenta
     * (como abono a deuda: no cuenta como gasto, porque reduce lo que debes).
     * [newBalance] es el saldo que reporta la entidad después del pago; si no se sabe,
     * se resta el valor pagado.
     */
    suspend fun registerDebtPayment(debtId: Long, amount: Money, accountId: Long?, newBalance: Money?, date: LocalDate): Long =
        db.withTransaction {
            require(amount.centavos > 0) { "El valor debe ser mayor que cero" }
            val debt = requireNotNull(dao.getDebt(debtId)) { "La deuda no existe" }
            val after = newBalance ?: Money(debt.balanceCentavos - amount.centavos).let { if (it.isNegative) Money.ZERO else it }
            require(!after.isNegative) { "El saldo no puede ser negativo" }
            val now = clock.millis()
            val at = BogotaTime.instantForDate(date, clock)
            val txId = accountId?.let {
                db.transactionDao().insert(
                    TransactionEntity(
                        accountId = it,
                        amountCentavos = -amount.centavos,
                        kind = TransactionKind.LOAN_REPAYMENT,
                        categoryId = null,
                        description = "Pago ${debt.name}",
                        counterparty = debt.creditor,
                        occurredAtEpochMs = at.toEpochMilli(),
                        origin = TransactionOrigin.MANUAL,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                )
            }
            dao.insertDebtPayment(DebtPaymentEntity(debtId = debtId, transactionId = txId, amountCentavos = amount.centavos, balanceAfterCentavos = after.centavos, paidAtEpochMs = at.toEpochMilli()))
            dao.updateDebt(
                debt.copy(
                    balanceCentavos = after.centavos,
                    status = if (after.isZero) DebtStatus.PAID.name else DebtStatus.ACTIVE.name,
                    remainingInstallments = debt.remainingInstallments?.let { (it - 1).coerceAtLeast(0) },
                ),
            )
            audit("debt", debtId, "payment", "${amount.formatCop()} -> saldo ${after.formatCop()}", now)
            txId ?: 0L
        }

    /** Cuántos pagos tiene cada deuda en [month] (una quincenal lleva dos cuotas al mes). */
    fun observeDebtPaymentCounts(month: YearMonth): Flow<Map<Long, Int>> {
        val from = month.atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant().toEpochMilli()
        return dao.observeDebtPaymentsBetween(from, to).map { list -> list.groupingBy { it.debtId }.eachCount() }
    }

    // ================================================================= metas y fondo

    fun observeGoals(): Flow<List<SavingsGoal>> = dao.observeGoals().map { list ->
        list.map { row ->
            val g = row.goal
            SavingsGoal(
                id = g.id,
                name = g.name,
                description = g.description,
                target = Money(g.targetCentavos),
                saved = Money(row.savedCentavos),
                deadline = g.deadlineEpochDay?.let(LocalDate::ofEpochDay),
                isEmergencyFund = g.isEmergencyFund,
                targetMonths = g.targetMonths,
                customTarget = g.customTarget,
                plannedMonthly = g.plannedMonthlyCentavos?.let(::Money),
                status = runCatching { GoalStatus.valueOf(g.status) }.getOrDefault(GoalStatus.ACTIVE),
            )
        }
    }

    fun observeContributions(goalId: Long): Flow<List<GoalContribution>> = dao.observeContributions(goalId).map { list ->
        list.map { GoalContribution(it.id, Money(it.amountCentavos), it.note, Instant.ofEpochMilli(it.createdAtEpochMs)) }
    }

    suspend fun saveGoal(id: Long?, name: String, description: String?, target: Money, deadline: LocalDate?, plannedMonthly: Money?): Long =
        db.withTransaction {
            val clean = name.trim()
            require(clean.isNotEmpty()) { "Escribe un nombre" }
            require(target.centavos > 0) { "El valor objetivo debe ser mayor que cero" }
            val now = clock.millis()
            val current = id?.let { dao.getGoal(it) }
            val entity = SavingsGoalEntity(
                id = id ?: 0,
                name = clean,
                description = description?.trim()?.ifEmpty { null },
                targetCentavos = target.centavos,
                deadlineEpochDay = deadline?.toEpochDay(),
                isEmergencyFund = current?.isEmergencyFund ?: false,
                targetMonths = current?.targetMonths,
                customTarget = current?.customTarget ?: true,
                plannedMonthlyCentavos = plannedMonthly?.centavos,
                status = current?.status ?: GoalStatus.ACTIVE.name,
                createdAtEpochMs = current?.createdAtEpochMs ?: now,
            )
            val savedId = if (current == null) dao.insertGoal(entity) else { dao.updateGoal(entity); current.id }
            refreshGoalStatus(savedId)
            audit("goal", savedId, if (current == null) "create" else "update", target.formatCop(), now)
            savedId
        }

    suspend fun deleteGoal(id: Long) = db.withTransaction {
        dao.deleteGoal(id)
        audit("goal", id, "delete", null, clock.millis())
    }

    /** Fondo de emergencia: se crea la primera vez que se abre su pantalla. */
    suspend fun emergencyFundId(): Long = db.withTransaction {
        dao.getEmergencyFund()?.id
            ?: dao.insertGoal(
                SavingsGoalEntity(
                    name = "Fondo de emergencia", description = null, targetCentavos = 0, deadlineEpochDay = null,
                    isEmergencyFund = true, targetMonths = 3, customTarget = false, plannedMonthlyCentavos = null,
                    status = GoalStatus.ACTIVE.name, createdAtEpochMs = clock.millis(),
                ),
            )
    }

    /** [customTarget] null = la meta se calcula con tus gastos esenciales × [targetMonths]. */
    suspend fun configureEmergencyFund(targetMonths: Int, customTarget: Money?, plannedMonthly: Money?) = db.withTransaction {
        require(targetMonths in 1..12) { "Elige entre 1 y 12 meses" }
        val id = emergencyFundId()
        val current = requireNotNull(dao.getGoal(id))
        dao.updateGoal(
            current.copy(
                targetMonths = targetMonths,
                customTarget = customTarget != null,
                targetCentavos = customTarget?.centavos ?: current.targetCentavos,
                plannedMonthlyCentavos = plannedMonthly?.centavos,
            ),
        )
    }

    /** Guarda la meta calculada del fondo (si no es personalizada) para mostrarla en Inicio. */
    suspend fun updateEmergencyTarget(target: Money) = db.withTransaction {
        val id = emergencyFundId()
        val current = requireNotNull(dao.getGoal(id))
        if (!current.customTarget && current.targetCentavos != target.centavos) dao.updateGoal(current.copy(targetCentavos = target.centavos))
    }

    /** Aporte (positivo) o retiro (negativo) de una meta. No mueve dinero entre cuentas: lo reserva. */
    suspend fun contribute(goalId: Long, amount: Money, note: String?) = db.withTransaction {
        require(!amount.isZero) { "El valor debe ser distinto de cero" }
        val saved = Money(dao.savedIn(goalId))
        require(!(saved + amount).isNegative) { "No puedes retirar más de lo ahorrado (${saved.formatCop()})" }
        val now = clock.millis()
        dao.insertContribution(GoalContributionEntity(goalId = goalId, amountCentavos = amount.centavos, note = note?.trim()?.ifEmpty { null }, createdAtEpochMs = now))
        refreshGoalStatus(goalId)
        audit("goal", goalId, if (amount.isNegative) "withdraw" else "contribute", amount.formatCop(), now)
    }

    private suspend fun refreshGoalStatus(goalId: Long) {
        val goal = dao.getGoal(goalId) ?: return
        if (goal.isEmergencyFund || goal.status == GoalStatus.PAUSED.name) return
        val achieved = goal.targetCentavos > 0 && dao.savedIn(goalId) >= goal.targetCentavos
        val status = if (achieved) GoalStatus.ACHIEVED.name else GoalStatus.ACTIVE.name
        if (status != goal.status) dao.updateGoal(goal.copy(status = status))
    }

    // ================================================================= presupuestos

    fun observeBudgets(): Flow<List<Budget>> = dao.observeBudgets().map { list ->
        list.map { Budget(it.budget.id, it.budget.categoryId, it.categoryName, it.isEssential, Money(it.budget.limitCentavos)) }
    }

    suspend fun setBudget(categoryId: Long, limit: Money) = db.withTransaction {
        require(limit.centavos > 0) { "El presupuesto debe ser mayor que cero" }
        val category = requireNotNull(db.categoryDao().getById(categoryId)) { "La categoría no existe" }
        require(category.parentId == null) { "Los presupuestos se ponen a categorías principales" }
        val current = dao.getBudgetFor(categoryId)
        if (current == null) dao.insertBudget(BudgetEntity(categoryId = categoryId, limitCentavos = limit.centavos, createdAtEpochMs = clock.millis()))
        else dao.updateBudget(current.copy(limitCentavos = limit.centavos))
    }

    suspend fun deleteBudget(id: Long) = dao.deleteBudget(id)

    // ================================================================= obligaciones

    fun observeObligations(): Flow<List<Obligation>> = dao.observeObligations().map { list ->
        list.map {
            Obligation(
                it.id, it.name, Money(it.amountCentavos), it.dayOfMonth, it.isIncome, it.categoryId, it.accountId, it.active,
                runCatching { PayCurrency.valueOf(it.currency) }.getOrDefault(PayCurrency.COP),
            )
        }
    }

    /** Ids de obligaciones ya pagadas (o ingresos ya recibidos) en [month]. */
    fun observePaidObligations(month: YearMonth): Flow<Set<Long>> = observeObligationPayments(month).map { it.keys }

    /** Obligaciones pagadas en [month] con el valor real pagado en pesos, si se registró. */
    fun observeObligationPayments(month: YearMonth): Flow<Map<Long, Money?>> =
        dao.observeObligationPayments(month.toString()).map { list -> list.associate { it.obligationId to it.amountCentavos?.let(::Money) } }

    suspend fun saveObligation(
        id: Long?,
        name: String,
        amount: Money,
        dayOfMonth: Int,
        isIncome: Boolean,
        categoryId: Long?,
        accountId: Long?,
        active: Boolean,
        currency: PayCurrency = PayCurrency.COP,
    ): Long =
        db.withTransaction {
            val clean = name.trim()
            require(clean.isNotEmpty()) { "Escribe un nombre" }
            require(amount.centavos > 0) { "El valor debe ser mayor que cero" }
            require(dayOfMonth in 1..31) { "El día debe estar entre 1 y 31" }
            val entity = ObligationEntity(
                id = id ?: 0, name = clean, amountCentavos = amount.centavos, dayOfMonth = dayOfMonth, isIncome = isIncome,
                categoryId = categoryId, accountId = accountId, active = active, currency = currency.name,
                createdAtEpochMs = id?.let { dao.getObligation(it)?.createdAtEpochMs } ?: clock.millis(),
            )
            if (id == null) dao.insertObligation(entity) else { dao.updateObligation(entity); id }
        }

    suspend fun deleteObligation(id: Long) = dao.deleteObligation(id)

    /**
     * Marca una obligación como pagada en [month]. Si se indica una cuenta, registra
     * también el movimiento (gasto o ingreso) en esa cuenta, en la fecha de hoy.
     * [paidAmount] es lo que realmente se pagó en pesos; es obligatorio para un pago
     * en dólares que se registra en una cuenta (el banco cobra con su propia tasa).
     */
    suspend fun markObligationPaid(obligationId: Long, month: YearMonth, accountId: Long?, paidAmount: Money? = null) = db.withTransaction {
        val o = requireNotNull(dao.getObligation(obligationId)) { "La obligación no existe" }
        require(paidAmount == null || paidAmount.centavos > 0) { "El valor pagado debe ser mayor que cero" }
        val usd = o.currency == PayCurrency.USD.name
        require(!(usd && accountId != null && paidAmount == null)) { "Escribe cuánto te cobraron en pesos" }
        val cop = paidAmount?.centavos ?: if (usd) null else o.amountCentavos
        val now = clock.millis()
        val txId = accountId?.let {
            db.transactionDao().insert(
                TransactionEntity(
                    accountId = it,
                    amountCentavos = if (o.isIncome) cop!! else -cop!!,
                    kind = if (o.isIncome) TransactionKind.INCOME else TransactionKind.EXPENSE,
                    categoryId = o.categoryId,
                    description = o.name,
                    occurredAtEpochMs = now,
                    origin = TransactionOrigin.MANUAL,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
        }
        dao.insertObligationPayment(
            ObligationPaymentEntity(obligationId = obligationId, period = month.toString(), transactionId = txId, paidAtEpochMs = now, amountCentavos = cop),
        )
    }

    suspend fun unmarkObligationPaid(obligationId: Long, month: YearMonth) = dao.deleteObligationPayment(obligationId, month.toString())

    // ================================================================= análisis

    /**
     * Promedios de los últimos [months] meses completos (si aún no hay meses completos,
     * usa el mes en curso). Todo sale de movimientos confirmados.
     */
    fun observeProfile(months: Int = 3): Flow<FinancialProfile> {
        val today = BogotaTime.today(clock)
        val current = YearMonth.from(today)
        val from = current.minusMonths(months.toLong()).atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant().toEpochMilli()
        val to = current.plusMonths(1).atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant().toEpochMilli()
        return combine(dao.observeMonthlyTotals(from, to, BOGOTA_OFFSET_SECONDS), observeDebts()) { rows, debts ->
            val complete = rows.filter { it.month != current.toString() && (it.expenseCentavos > 0 || it.incomeCentavos > 0) }
            val used = complete.ifEmpty { rows.filter { it.month == current.toString() } }
            val n = used.size.coerceAtLeast(1)
            val income = Money(used.sumOf { it.incomeCentavos } / n)
            val expense = Money(used.sumOf { it.expenseCentavos } / n)
            val essential = Money(used.sumOf { it.essentialCentavos } / n)
            val debtPayments = debts.filter { it.status == DebtStatus.ACTIVE }.map { it.monthlyPayment }.sum()
            FinancialProfile(used.size, income, expense, essential, debtPayments, income - expense - debtPayments)
        }
    }

    /** Gasto mensual promedio por categoría principal, para sugerir presupuestos. */
    suspend fun categoryHistory(months: Int = 3): List<CategoryHistory> {
        val today = BogotaTime.today(clock)
        val current = YearMonth.from(today)
        val from = current.minusMonths(months.toLong()).atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant().toEpochMilli()
        val to = current.plusMonths(1).atDay(1).atStartOfDay(BogotaTime.ZONE).toInstant().toEpochMilli()
        val rows = dao.observeMonthlyCategorySpending(from, to, BOGOTA_OFFSET_SECONDS).first()
        val complete = rows.filter { it.month != current.toString() }
        val used = complete.ifEmpty { rows }
        val monthsCount = used.map { it.month }.distinct().size.coerceAtLeast(1)
        val categories = db.categoryDao().observeAll().first().associateBy { it.id }
        return used.groupBy { it.categoryId }.mapNotNull { (id, list) ->
            val c = categories[id] ?: return@mapNotNull null
            CategoryHistory(id, c.name, c.isEssential, Money(list.sumOf { it.amountCentavos } / monthsCount))
        }
    }

    private suspend fun audit(type: String, id: Long, action: String, detail: String?, now: Long) {
        db.transactionDao().insertAudit(AuditLogEntity(entityType = type, entityId = id, action = action, detail = detail, createdAtEpochMs = now))
    }

    private fun DebtEntity.toModel() = Debt(
        id = id, name = name, creditor = creditor, initial = Money(initialCentavos), balance = Money(balanceCentavos),
        rateBasisPoints = rateBasisPoints, rateType = runCatching { RateType.valueOf(rateType) }.getOrDefault(RateType.NONE),
        installment = Money(monthlyPaymentCentavos),
        frequency = runCatching { PaymentFrequency.valueOf(paymentFrequency) }.getOrDefault(PaymentFrequency.MONTHLY), dueDay = dueDay, remainingInstallments = remainingInstallments,
        status = runCatching { DebtStatus.valueOf(status) }.getOrDefault(DebtStatus.ACTIVE), notes = notes,
    )

    companion object {
        /** Colombia no tiene horario de verano: siempre UTC-5. */
        const val BOGOTA_OFFSET_SECONDS = -5L * 3600
    }
}
