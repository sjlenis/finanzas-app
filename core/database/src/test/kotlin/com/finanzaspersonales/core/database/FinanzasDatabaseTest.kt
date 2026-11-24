package com.finanzaspersonales.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finanzaspersonales.core.database.entity.TransactionEntity
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.core.model.TransactionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pruebas del esquema y de las reglas de saldo. Se usa una base en memoria sin cifrado
 * porque la librería nativa de SQLCipher no corre en la JVM; el cifrado se prueba en el dispositivo.
 */
@RunWith(RobolectricTestRunner::class)
class FinanzasDatabaseTest {

    private lateinit var db: FinanzasDatabase
    private val now = 1_764_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FinanzasDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DefaultData.insertInto(db, now)
            })
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun accountId(name: String) =
        db.accountDao().observeActiveWithBalance().first().first { it.account.name == name }.account.id

    private fun tx(accountId: Long, amount: Long, kind: TransactionKind, status: TransactionStatus = TransactionStatus.CONFIRMED) =
        TransactionEntity(
            accountId = accountId, amountCentavos = amount, kind = kind, categoryId = null,
            description = "prueba", occurredAtEpochMs = now, origin = TransactionOrigin.MANUAL,
            status = status, createdAtEpochMs = now, updatedAtEpochMs = now,
        )

    @Test
    fun creaCuentasYCategoriasIniciales() = runTest {
        val accounts = db.accountDao().observeActiveWithBalance().first()
        assertEquals(listOf("Bancolombia", "Nequi", "Efectivo"), accounts.map { it.account.name })
        assertEquals(DefaultData.categories.size, db.categoryDao().observeAll().first().size)
    }

    @Test
    fun saldoSeCalculaDesdeLosMovimientosConfirmados() = runTest {
        val nequi = accountId("Nequi")
        db.transactionDao().insert(tx(nequi, 25_000_000, TransactionKind.INCOME))
        db.transactionDao().insert(tx(nequi, -1_800_000, TransactionKind.EXPENSE))
        db.transactionDao().insert(tx(nequi, -999_900, TransactionKind.EXPENSE, TransactionStatus.PENDING))

        val balance = db.accountDao().observeActiveWithBalance().first().first { it.account.id == nequi }
        assertEquals(23_200_000L, balance.balanceCentavos) // el pendiente no cuenta
    }

    @Test
    fun transferenciaPropiaNoEsIngresoNiGasto() = runTest {
        val banco = accountId("Bancolombia")
        val efectivo = accountId("Efectivo")
        db.transactionDao().insert(tx(banco, 50_000_000, TransactionKind.INCOME))
        db.transactionDao().createTransfer(banco, efectivo, 10_000_000, now, "Retiro cajero", now)

        val balances = db.accountDao().observeActiveWithBalance().first().associate { it.account.id to it.balanceCentavos }
        assertEquals(40_000_000L, balances[banco])
        assertEquals(10_000_000L, balances[efectivo])

        val totals = db.transactionDao().observeTotals(now - 1, now + 1).first()
        assertEquals(50_000_000L, totals.incomeCentavos)
        assertEquals(0L, totals.expenseCentavos)
    }

    @Test
    fun devolucionRestaDelGastoYComisionSuma() = runTest {
        val nequi = accountId("Nequi")
        db.transactionDao().insert(tx(nequi, -12_000_000, TransactionKind.EXPENSE))
        db.transactionDao().insert(tx(nequi, 3_000_000, TransactionKind.REFUND))
        db.transactionDao().insert(tx(nequi, -500_000, TransactionKind.FEE))

        val totals = db.transactionDao().observeTotals(now - 1, now + 1).first()
        assertEquals(9_500_000L, totals.expenseCentavos)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferenciaALaMismaCuentaSeRechaza() = runTest {
        val nequi = accountId("Nequi")
        db.transactionDao().createTransfer(nequi, nequi, 100, now, "x", now)
    }
}
