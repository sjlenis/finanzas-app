package com.finanzaspersonales.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finanzaspersonales.core.common.ClosedOpenRange
import com.finanzaspersonales.core.database.DefaultData
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class NotificationImporterTest {

    private val now = Instant.parse("2026-11-24T21:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val all = ClosedOpenRange(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"))
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: FinanzasDatabase
    private lateinit var prefs: UserPreferences
    private lateinit var importer: NotificationImporter
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository

    private val nequi = "com.nequi.MobileApp"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FinanzasDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DefaultData.insertInto(db, now.toEpochMilli())
            })
            .build()
        val file = java.io.File.createTempFile("prefs", ".preferences_pb").apply { delete() }
        prefs = UserPreferences(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = prefsScope) { file },
        )
        kotlinx.coroutines.runBlocking {
            prefs.setNotificationDetection(true)
            prefs.setNotificationSamples(false)
        }
        importer = NotificationImporter(db, prefs, clock)
        transactions = TransactionRepository(db, clock)
        accounts = AccountRepository(db, clock)
    }

    private val prefsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    @After
    fun tearDown() {
        db.close()
        prefsScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun balance(name: String) = accounts.observeAccounts().first().first { it.name == name }.balance

    @Test
    fun pagoDetectadoQuedaPendienteYNoAfectaElSaldo() = runTest {
        assertEquals(ImportOutcome.PENDING_CREATED, importer.process(nequi, "Nequi", "Pagaste \$12.500 en Tienda D1.", now))
        val pending = transactions.observePending().first().single()
        assertEquals(TransactionStatus.PENDING, pending.status)
        assertEquals(Money.ofPesos(-12_500), pending.amount)
        assertEquals("Tienda D1", pending.description)
        assertEquals(Money.ZERO, balance("Nequi"))

        transactions.confirmPending(pending.id)
        assertEquals(Money.ofPesos(-12_500), balance("Nequi"))
        assertTrue(transactions.observePending().first().isEmpty())
    }

    @Test
    fun elMismoAvisoNoSeRegistraDosVeces() = runTest {
        importer.process(nequi, null, "Pagaste \$5.000 en Café.", now)
        assertEquals(ImportOutcome.ALREADY_IMPORTED, importer.process(nequi, null, "Pagaste \$5.000 en Café.", now.plusSeconds(10)))
        assertEquals(1, transactions.observePending().first().size)
    }

    @Test
    fun avisoParecidoDeOtraFuenteSeMarcaComoPosibleDuplicado() = runTest {
        importer.process("com.todo1.mobile", null, "Compraste \$40.000 en Exito.", now)
        importer.process("com.samsung.android.messaging", null, "Bancolombia le informa Compra por \$40.000,00 en EXITO 16:00.", now.plusSeconds(60))
        val notes = transactions.observePending().first().map { it.notes.orEmpty() }
        assertEquals(2, notes.size)
        assertTrue(notes.any { it.contains("Posible duplicado") })
    }

    @Test
    fun apagadoNoLeeNada() = runTest {
        prefs.setNotificationDetection(false)
        assertEquals(ImportOutcome.DISABLED, importer.process(nequi, null, "Pagaste \$5.000 en Café.", now))
        assertTrue(transactions.observe(all).first().isEmpty())
    }

    @Test
    fun retiroConfirmadoEsTransferenciaAEfectivo() = runTest {
        importer.process("com.todo1.mobile", null, "Retiraste \$100.000 en cajero.", now)
        val pending = transactions.observePending().first().single()
        assertEquals(TransactionKind.WITHDRAWAL, pending.kind)
        val cash = accounts.observeAccounts().first().first { it.name == "Efectivo" }.id
        transactions.confirmPending(pending.id, cashAccountId = cash)
        assertEquals(Money.ofPesos(-100_000), balance("Bancolombia"))
        assertEquals(Money.ofPesos(100_000), balance("Efectivo"))
    }

    @Test
    fun envioEntreMisCuentasSeConvierteEnTransferencia() = runTest {
        importer.process("com.todo1.mobile", null, "Transferiste \$50.000 a Nequi.", now)
        val pending = transactions.observePending().first().single()
        val nequiId = accounts.observeAccounts().first().first { it.name == "Nequi" }.id
        transactions.pendingToTransfer(pending.id, nequiId)
        assertEquals(Money.ofPesos(-50_000), balance("Bancolombia"))
        assertEquals(Money.ofPesos(50_000), balance("Nequi"))
        assertTrue(transactions.totals(all).expense.isZero)
    }

    @Test
    fun muestrasSoloSiEstanActivadasYSinCodigos() = runTest {
        importer.process(nequi, null, "Pagaste \$5.000 en Café.", now)
        assertTrue(importer.observeSamples().first().isEmpty())
        prefs.setNotificationSamples(true)
        importer.process(nequi, null, "Tu código de verificación es 4821", now)
        importer.process(nequi, null, "Pagaste \$7.000 en Tienda *1234.", now)
        val samples = importer.observeSamples().first()
        assertEquals(1, samples.size)
        assertFalse(samples.single().maskedText.contains("1234"))
    }
}
