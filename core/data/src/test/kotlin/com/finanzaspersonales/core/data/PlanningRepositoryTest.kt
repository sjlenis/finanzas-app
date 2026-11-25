package com.finanzaspersonales.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finanzaspersonales.core.common.ClosedOpenRange
import com.finanzaspersonales.core.database.DefaultData
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.model.ManualEntryType
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.engine.finance.RateType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class PlanningRepositoryTest {

    private val now = Instant.parse("2026-11-24T21:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val all = ClosedOpenRange(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"))
    private lateinit var db: FinanzasDatabase
    private lateinit var planning: PlanningRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FinanzasDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DefaultData.insertInto(db, now.toEpochMilli())
            })
            .build()
        planning = PlanningRepository(db, clock)
        transactions = TransactionRepository(db, clock)
        accounts = AccountRepository(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
        prefsScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private val prefsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private fun newPrefs(): UserPreferences {
        val file = java.io.File.createTempFile("prefs", ".preferences_pb").apply { delete() }
        return UserPreferences(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = prefsScope) { file })
    }

    private suspend fun accountId(name: String) = accounts.observeAccounts().first().first { it.name == name }.id
    private fun pesos(p: Long) = Money.ofPesos(p)

    @Test
    fun pagoDeDeudaBajaElSaldoYNoEsGasto() = runTest {
        val id = planning.saveDebt(null, "Tarjeta", "Bancolombia", pesos(1_000_000), pesos(1_000_000), 2_800, RateType.EFFECTIVE_ANNUAL, pesos(100_000), 15, 10, null)
        planning.registerDebtPayment(id, pesos(100_000), accountId("Bancolombia"), null, LocalDate.of(2026, 11, 24))
        val debt = planning.observeDebts().first().single()
        assertEquals(pesos(900_000), debt.balance)
        assertEquals(9, debt.remainingInstallments)
        assertEquals(10, debt.paidPercent)
        assertEquals(pesos(-100_000), accounts.observeAccounts().first().first { it.name == "Bancolombia" }.balance)
        assertTrue(transactions.totals(all).expense.isZero)
    }

    @Test
    fun saldoReportadoPorElBancoGanaYLaDeudaQuedaPagada() = runTest {
        val id = planning.saveDebt(null, "Préstamo", null, pesos(300_000), pesos(300_000), 0, RateType.NONE, pesos(100_000), null, null, null)
        planning.registerDebtPayment(id, pesos(310_000), null, Money.ZERO, LocalDate.of(2026, 11, 24))
        assertEquals(DebtStatus.PAID, planning.observeDebts().first().single().status)
    }

    @Test
    fun metasNoPermitenRetirarMasDeLoAhorrado() = runTest {
        val id = planning.saveGoal(null, "Computador", null, pesos(3_000_000), LocalDate.of(2027, 6, 1), null)
        planning.contribute(id, pesos(500_000), null)
        val error = runCatching { planning.contribute(id, pesos(-600_000), null) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        planning.contribute(id, pesos(2_500_000), "Prima")
        val goal = planning.observeGoals().first().single()
        assertEquals(pesos(3_000_000), goal.saved)
        assertEquals(GoalStatus.ACHIEVED, goal.status)
    }

    @Test
    fun fondoDeEmergenciaSeCreaUnaSolaVez() = runTest {
        val a = planning.emergencyFundId()
        val b = planning.emergencyFundId()
        assertEquals(a, b)
        planning.configureEmergencyFund(6, null, pesos(200_000))
        val fund = planning.observeGoals().first().single { it.isEmergencyFund }
        assertEquals(6, fund.targetMonths)
        assertEquals(pesos(200_000), fund.plannedMonthly)
    }

    @Test
    fun perfilUsaMesesCompletosYCuotasDeDeudas() = runTest {
        val nequi = accountId("Nequi")
        val food = db.categoryDao().findTopLevelByName("Alimentación")!!.id
        val fun_ = db.categoryDao().findTopLevelByName("Entretenimiento")!!.id
        // Octubre: ingreso 2.000.000, comida 600.000 (esencial), cine 100.000.
        val october = Instant.parse("2026-10-15T17:00:00Z")
        transactions.saveManual(null, ManualEntryType.INCOME, pesos(2_000_000), nequi, null, "Salario", october, null)
        transactions.saveManual(null, ManualEntryType.EXPENSE, pesos(600_000), nequi, food, "Mercado", october, null)
        transactions.saveManual(null, ManualEntryType.EXPENSE, pesos(100_000), nequi, fun_, "Cine", october, null)
        // Noviembre (mes en curso): no entra en el promedio porque hay meses completos.
        transactions.saveManual(null, ManualEntryType.EXPENSE, pesos(999_000), nequi, fun_, "Viaje", now, null)
        planning.saveDebt(null, "Tarjeta", null, pesos(1_000_000), pesos(1_000_000), 0, RateType.NONE, pesos(150_000), null, null, null)

        val p = planning.observeProfile().first()
        assertEquals(1, p.monthsAnalyzed)
        assertEquals(pesos(2_000_000), p.averageIncome)
        assertEquals(pesos(700_000), p.averageExpense)
        assertEquals(pesos(600_000), p.averageEssential)
        assertEquals(pesos(1_150_000), p.monthlySurplus)

        val history = planning.categoryHistory().associateBy { it.name }
        assertEquals(pesos(600_000), history.getValue("Alimentación").monthlyAverage)
    }

    @Test
    fun obligacionPagadaRegistraElGastoUnaVezPorMes() = runTest {
        val rent = db.categoryDao().findTopLevelByName("Vivienda")!!.id
        val id = planning.saveObligation(null, "Arriendo", pesos(800_000), 5, false, rent, accountId("Bancolombia"), true)
        val month = YearMonth.of(2026, 11)
        planning.markObligationPaid(id, month, accountId("Bancolombia"))
        assertEquals(setOf(id), planning.observePaidObligations(month).first())
        assertEquals(pesos(800_000), transactions.totals(all).expense)
    }

    @Test
    fun presupuestoUnoPorCategoria() = runTest {
        val food = db.categoryDao().findTopLevelByName("Alimentación")!!.id
        planning.setBudget(food, pesos(500_000))
        planning.setBudget(food, pesos(600_000))
        assertEquals(listOf(pesos(600_000)), planning.observeBudgets().first().map { it.limit })
    }

    @Test
    fun dineroRealmenteDisponibleDelEjemplo() = runTest {
        // Ejemplo del requerimiento: 2.000.000 - 300.000 servicios - 400.000 cuotas - 200.000 emergencias = 1.100.000.
        val banco = accountId("Bancolombia")
        accounts.update(banco, "Bancolombia", com.finanzaspersonales.core.model.AccountType.BANK, "Bancolombia", pesos(2_000_000))
        planning.saveObligation(null, "Servicios", pesos(300_000), 28, false, null, banco, true)
        planning.saveDebt(null, "Crédito", null, pesos(4_000_000), pesos(4_000_000), 0, RateType.NONE, pesos(400_000), 30, null, null)
        planning.contribute(planning.emergencyFundId(), pesos(200_000), null)
        val overview = OverviewRepository(accounts, planning, transactions, newPrefs(), clock)

        val o = overview.observe().first()
        assertEquals(pesos(2_000_000), o.money.total)
        assertEquals(pesos(700_000), o.money.obligations)
        assertEquals(pesos(1_100_000), o.money.available)

        // Pagar la obligación descuenta el dinero de la cuenta y deja de estar pendiente: no se resta dos veces.
        planning.markObligationPaid(o.pending.first { it.name == "Servicios" }.id, YearMonth.of(2026, 11), banco)
        val after = overview.observe().first()
        assertEquals(pesos(1_700_000), after.money.total)
        assertEquals(pesos(400_000), after.money.obligations)
        assertEquals(pesos(1_100_000), after.money.available)
    }

    @Test
    fun deudaQuincenalTieneDosCuotasAlMes() = runTest {
        val id = planning.saveDebt(
            null, "Préstamo", null, pesos(1_000_000), pesos(1_000_000), 0, RateType.NONE, pesos(50_000), 15, null, null,
            frequency = PaymentFrequency.BIWEEKLY,
        )
        val debt = planning.observeDebt(id).first()!!
        assertEquals(pesos(100_000), debt.monthlyPayment)
        assertEquals(listOf(15, 30), debt.dueDays)
        assertEquals(pesos(100_000), planning.observeProfile().first().debtPayments)

        val overview = OverviewRepository(accounts, planning, transactions, newPrefs(), clock)
        val items = overview.observe().first().monthItems.filter { it.type == UpcomingType.DEBT }
        assertEquals(2, items.size)
        assertEquals(listOf(15, 30), items.map { it.date.dayOfMonth })

        // Un pago en el mes cubre la primera quincena; la segunda sigue pendiente.
        planning.registerDebtPayment(id, pesos(50_000), null, null, java.time.LocalDate.of(2026, 11, 15))
        val after = overview.observe().first().monthItems.filter { it.type == UpcomingType.DEBT }
        assertEquals(listOf(true, false), after.map { it.paid })
    }

    @Test
    fun pagoEnDolaresSeEstimaConLaTasaYSeRegistraLoCobrado() = runTest {
        val prefs = newPrefs()
        val overview = OverviewRepository(accounts, planning, transactions, prefs, clock)
        val banco = accountId("Bancolombia")
        // 12,99 dólares = 1.299 centavos de dólar.
        val id = planning.saveObligation(null, "Streaming", Money(1_299), 28, false, null, banco, true, PayCurrency.USD)

        val sinTasa = overview.observe().first()
        assertTrue(sinTasa.missingUsdRate)
        assertEquals(Money(1_299), sinTasa.pending.single().usdAmount)

        prefs.setUsdRate(pesos(4_000))
        val conTasa = overview.observe().first()
        assertEquals(pesos(51_960), conTasa.pending.single().amount)

        // En una cuenta hay que decir cuánto cobró el banco en pesos.
        assertTrue(runCatching { planning.markObligationPaid(id, YearMonth.of(2026, 11), banco) }.isFailure)
        planning.markObligationPaid(id, YearMonth.of(2026, 11), banco, pesos(53_100))
        val pagado = overview.observe().first()
        assertEquals(pesos(-53_100), pagado.money.total)
        assertEquals(pesos(53_100), pagado.monthItems.single().amount)
        assertTrue(pagado.pending.isEmpty())
    }
}
