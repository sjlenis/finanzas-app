package com.finanzaspersonales.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finanzaspersonales.core.database.DefaultData
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.model.Money
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
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class ConfigImporterTest {

    private val now = Instant.parse("2026-11-25T21:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var db: FinanzasDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var planning: PlanningRepository
    private lateinit var importer: ConfigImporter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FinanzasDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DefaultData.insertInto(db, now.toEpochMilli())
            })
            .build()
        accounts = AccountRepository(db, clock)
        planning = PlanningRepository(db, clock)
        importer = ConfigImporter(
            accounts, CategoryRepository(db), planning,
            UserPreferences(ApplicationProvider.getApplicationContext<Context>()), clock,
        )
    }

    @After
    fun tearDown() = db.close()

    private val json = """
        {"formato":"finanzas-config","version":1,"mes":"2026-11",
         "cuentas":[{"nombre":"Nequi","tipo":"WALLET","saldo":12000},{"nombre":"Ahorro","tipo":"WALLET","saldo":200000}],
         "fondoEmergencia":200000,
         "fijos":[
           {"nombre":"Salario","tipo":"INGRESO","valor":100000,"dia":15,"yaPasoEsteMes":true},
           {"nombre":"Salario segunda quincena","tipo":"INGRESO","valor":100000,"dia":30},
           {"nombre":"Suscripción","tipo":"PAGO","valor":"20","moneda":"USD","dia":15,"yaPasoEsteMes":true}
         ],
         "deudas":[{"nombre":"Tarjeta","total":200000,"saldo":200000,"cuota":50000,"dia":8,"pagadaEsteMes":true}]}
    """.trimIndent()

    @Test
    fun aplicaSinDuplicarYSeMarcaLoDelMes() = runTest {
        val plan = ConfigPlan.parse(json)
        importer.apply(plan)
        importer.apply(plan) // dos veces: no duplica

        val list = accounts.observeAccounts().first()
        assertEquals(Money.ofPesos(12_000), list.first { it.name == "Nequi" }.balance)
        assertEquals(Money.ofPesos(200_000), list.first { it.name == "Ahorro" }.balance)
        assertEquals(1, list.count { it.name == "Ahorro" })
        assertEquals(Money.ofPesos(200_000), planning.observeGoals().first().single { it.isEmergencyFund }.saved)

        val obligations = planning.observeObligations().first()
        assertEquals(3, obligations.size)
        assertEquals(Money(2_000), obligations.first { it.name == "Suscripción" }.amount)
        val paid = planning.observePaidObligations(YearMonth.of(2026, 11)).first()
        assertEquals(setOf("Salario", "Suscripción"), obligations.filter { it.id in paid }.map { it.name }.toSet())

        val debt = planning.observeDebts().first().single()
        assertEquals(Money.ofPesos(200_000), debt.balance)
        assertEquals(1, planning.observeDebtPaymentCounts(YearMonth.of(2026, 11)).first()[debt.id])
    }

    @Test
    fun archivoInvalidoDaUnMensajeClaro() {
        val error = runCatching { ConfigPlan.parse("""{"formato":"otro"}""") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("no es una configuración"))
    }
}
