package com.finanzaspersonales.feature.assistant

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finanzaspersonales.core.common.ClosedOpenRange
import com.finanzaspersonales.core.data.AccountRepository
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.data.TransactionRepository
import com.finanzaspersonales.core.database.DefaultData
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.model.Money
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionOrigin
import com.finanzaspersonales.engine.nlp.DraftKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class FinancialAssistantTest {

    // 24 de noviembre de 2026, 4:00 p. m. en Colombia.
    private val now = Instant.parse("2026-11-24T21:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val all = ClosedOpenRange(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"))
    private lateinit var db: FinanzasDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var assistant: FinancialAssistant

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FinanzasDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DefaultData.insertInto(db, now.toEpochMilli())
            })
            .build()
        accounts = AccountRepository(db, clock)
        categories = CategoryRepository(db)
        transactions = TransactionRepository(db, clock)
        assistant = FinancialAssistant(transactions, com.finanzaspersonales.core.data.PlanningRepository(db, clock), clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun data(defaultName: String? = null): AssistantData {
        val list = accounts.observeAccounts().first()
        return AssistantData(list, categories.observeAll().first(), list.firstOrNull { it.name == defaultName }?.id)
    }

    private suspend fun balance(name: String) = accounts.observeAccounts().first().first { it.name == name }.balance

    @Test
    fun gastoConCuentaPredeterminadaSeGuardaDesdeElChat() = runTest {
        val d = data(defaultName = "Nequi")
        val reply = assistant.reply("Hoy gasté 15.000 en almuerzo", d)
        assertEquals(CardStatus.PENDING, reply.status)
        val card = requireNotNull(reply.card)
        assertEquals(DraftKind.EXPENSE, card.kind)
        assertEquals("Nequi", card.accountName)
        assertTrue(card.accountIsDefault)
        assertEquals("Alimentación", card.categoryName)

        assistant.save(card, d)
        assertEquals(Money.ofPesos(-15_000), balance("Nequi"))
        val saved = transactions.observe(all).first().single()
        assertEquals(TransactionOrigin.CHAT, saved.origin)
        assertEquals("Hoy has gastado \$15.000.", assistant.answer(
            com.finanzaspersonales.engine.nlp.FinanceQuery.Spent(com.finanzaspersonales.core.common.Period.DAY, null), d,
        ))
    }

    @Test
    fun laCuentaDelMensajeGanaALaPredeterminada() = runTest {
        val card = requireNotNull(assistant.reply("Pagué 20 mil de taxi con Bancolombia", data("Nequi")).card)
        assertEquals("Bancolombia", card.accountName)
        assertEquals(false, card.accountIsDefault)
    }

    @Test
    fun sinValorPreguntaCuantoFue() = runTest {
        val reply = assistant.reply("Compré un mercado", data())
        assertEquals(CardStatus.AWAITING_AMOUNT, reply.status)
        assertNull(reply.card?.amount)
        assertEquals(Money.ofPesos(85_000), assistant.amountReply("85 mil"))
    }

    @Test
    fun retiroEsTransferenciaAEfectivoYNoEsGasto() = runTest {
        val d = data("Bancolombia")
        val card = requireNotNull(assistant.reply("Retiré 200 mil del cajero", d).card)
        assertEquals(DraftKind.WITHDRAWAL, card.kind)
        assertEquals("Efectivo", card.toAccountName)
        assistant.save(card, d)
        assertEquals(Money.ofPesos(-200_000), balance("Bancolombia"))
        assertEquals(Money.ofPesos(200_000), balance("Efectivo"))
        assertTrue(transactions.totals(all).expense.isZero)
    }

    @Test
    fun prestamoMueveSaldoPeroNoEsGasto() = runTest {
        val d = data("Nequi")
        val card = requireNotNull(assistant.reply("Le presté 50 mil a mi hermano", d).card)
        assertEquals(DraftKind.LOAN_GIVEN, card.kind)
        assertEquals("Préstamos", card.categoryName)
        assistant.save(card, d)
        assertEquals(Money.ofPesos(-50_000), balance("Nequi"))
        val tx = transactions.observe(all).first().single()
        assertEquals(TransactionKind.LOAN_GIVEN, tx.kind)
        assertTrue(transactions.totals(all).expense.isZero)
    }

    @Test
    fun ingresoYConsultaDeSaldo() = runTest {
        val d = data("Bancolombia")
        assistant.save(requireNotNull(assistant.reply("Me pagaron 2.500.000 de salario", d).card), d)
        val answer = assistant.answer(com.finanzaspersonales.engine.nlp.FinanceQuery.AccountBalance("Bancolombia"), data("Bancolombia"))
        assertTrue(answer, answer.contains("\$2.500.000"))
    }

    @Test
    fun proximosPagosSinDeudasNoInventaDatos() = runTest {
        val answer = assistant.reply("¿Cuáles son mis próximos pagos?", data()).text
        assertTrue(answer, answer.contains("No tienes cuotas"))
    }

    @Test
    fun noGuardaFechasFuturas() = runTest {
        val d = data("Nequi")
        val card = requireNotNull(assistant.reply("Gasté 10 mil en pan", d).card).copy(date = java.time.LocalDate.of(2026, 12, 1))
        val error = runCatching { assistant.save(card, d) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(transactions.observe(all).first().isEmpty())
    }

    @Test
    fun tarjetaSeGuardaYLeeEnJson() {
        val card = DraftCard(
            kind = DraftKind.EXPENSE, amount = Money(1_550_050), categoryId = 3, categoryName = "Transporte",
            description = "Taxi", date = java.time.LocalDate.of(2026, 11, 20), accountId = 2, accountName = "Nequi",
            accountIsDefault = true, counterparty = null,
        )
        assertEquals(card, DraftCard.fromJson(card.toJson()))
        assertNull(DraftCard.fromJson("{dañado"))
    }
}
