package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.common.BogotaTime
import com.finanzaspersonales.core.database.dao.AccountDao
import com.finanzaspersonales.core.database.dao.TransactionDao
import com.finanzaspersonales.core.model.AccountSummary
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.PeriodSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Lectura de saldos y totales para el panel de inicio. */
@Singleton
class FinanceRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val clock: Clock,
) {
    fun observeAccounts(): Flow<List<AccountSummary>> =
        accountDao.observeActiveWithBalance().map { rows ->
            rows.map {
                AccountSummary(
                    id = it.account.id,
                    name = it.account.name,
                    type = it.account.type,
                    balance = Money(it.balanceCentavos),
                )
            }
        }

    /** Ingresos y gastos reales del mes en curso, en hora de Colombia. */
    fun observeCurrentMonth(): Flow<PeriodSummary> {
        val range = BogotaTime.monthRange(BogotaTime.today(clock))
        return transactionDao.observeTotals(range.start.toEpochMilli(), range.endExclusive.toEpochMilli())
            .map { PeriodSummary(Money(it.incomeCentavos), Money(it.expenseCentavos)) }
    }
}
