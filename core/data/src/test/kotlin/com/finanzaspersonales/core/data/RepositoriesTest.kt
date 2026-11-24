package com.finanzaspersonales.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finanzaspersonales.core.common.ClosedOpenRange
import com.finanzaspersonales.core.database.DefaultData
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.model.CategoryClass
import com.finanzaspersonales.core.model.ManualEntryType
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.TransactionKind
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
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class RepositoriesTest {

    private val now = Instant.parse("2026-11-24T21:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val all = ClosedOpenRange(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"))
    private lateinit var db: FinanzasDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FinanzasDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DefaultData.insertInto(db, now.toEpochMilli())
            })
            .build()
        accounts = AccountRepository(db, clock)
        transactions = TransactionRepository(db, clock)
        categories = CategoryRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun id(name: String) = accounts.observeAccounts().first().first { it.name == name }.id
    private suspend fun balance(name: String) = accounts.observeAccounts().first().first { it.name == name }.balance

    @Test
    fun editarUnGastoMantieneElSignoNegativo() = runTest {
        val nequi = id("Nequi")
        val txId = transactions.saveManual(null, ManualEntryType.EXPENSE, Money.ofPesos(18_000), nequi, null, "Almuerzo", now, null)
        assertEquals(Money.ofPesos(-18_000), balance("Nequi"))
        transactions.saveManual(txId, ManualEntryType.EXPENSE, Money.ofPesos(20_000), nequi, null, "Almuerzo", now, null)
        assertEquals(Money.ofPesos(-20_000), balance("Nequi"))
        transactions.saveManual(txId, ManualEntryType.INCOME, Money.ofPesos(20_000), nequi, null, "Era un ingreso", now, null)
        assertEquals(Money.ofPesos(20_000), balance("Nequi"))
    }

    @Test
    fun editarYBorrarTransferenciaAfectaAmbasCuentas() = runTest {
        val banco = id("Bancolombia")
        val efectivo = id("Efectivo")
        accounts.update(banco, "Bancolombia", com.finanzaspersonales.core.model.AccountType.BANK, "Bancolombia", Money.ofPesos(500_000))
        val transferId = transactions.saveTransfer(null, banco, efectivo, Money.ofPesos(100_000), "Retiro", now)
        transactions.saveTransfer(transferId, banco, efectivo, Money.ofPesos(150_000), "Retiro", now)
        assertEquals(Money.ofPesos(350_000), balance("Bancolombia"))
        assertEquals(Money.ofPesos(150_000), balance("Efectivo"))

        val leg = transactions.observe(all).first().first { it.kind == TransactionKind.TRANSFER }
        transactions.delete(leg.id)
        assertEquals(Money.ofPesos(500_000), balance("Bancolombia"))
        assertEquals(Money.ZERO, balance("Efectivo"))
        assertTrue(transactions.observe(all).first().isEmpty())
    }

    @Test
    fun conciliacionCreaAjusteQueNoEsIngresoNiGasto() = runTest {
        val nequi = id("Nequi")
        transactions.saveManual(null, ManualEntryType.INCOME, Money.ofPesos(100_000), nequi, null, "Pago", now, null)
        val diff = accounts.reconcile(nequi, Money.ofPesos(97_500))
        assertEquals(Money.ofPesos(-2_500), diff)
        assertEquals(Money.ofPesos(97_500), balance("Nequi"))
        assertEquals(Money.ZERO, accounts.reconcile(nequi, Money.ofPesos(97_500)))

        val activity = accounts.observeActivity(nequi, all).first()
        assertEquals(Money.ofPesos(100_000), activity.income)
        assertEquals(Money.ZERO, activity.expense)
    }

    @Test
    fun gastosPorCategoriaAgrupanSubcategorias() = runTest {
        val nequi = id("Nequi")
        val alimentacion = categories.observeAll().first().first { it.name == "Alimentación" }.id
        val cafe = categories.create("Café", alimentacion, CategoryClass.EXPENSE, isEssential = false)
        transactions.saveManual(null, ManualEntryType.EXPENSE, Money.ofPesos(8_000), nequi, cafe, "Café", now, null)
        transactions.saveManual(null, ManualEntryType.EXPENSE, Money.ofPesos(15_000), nequi, alimentacion, "Almuerzo", now, null)
        val spending = transactions.observeSpendingByCategory(all).first()
        assertEquals(1, spending.size)
        assertEquals("Alimentación", spending[0].categoryName)
        assertEquals(Money.ofPesos(23_000), spending[0].amount)
        // Filtrar por la categoría principal trae también la subcategoría.
        assertEquals(2, transactions.observe(all, categoryId = alimentacion).first().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun noSePermitenCategoriasDuplicadas() = runTest {
        categories.create("alimentación", null, CategoryClass.EXPENSE, isEssential = false)
    }
}
