package com.finanzaspersonales.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.finanzaspersonales.core.database.entity.ChatMessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Simula a un usuario que viene de la versión 0.3 (esquema 1): sus cuentas y
 * movimientos deben seguir ahí después de migrar, y la tabla del chat debe quedar
 * exactamente como Room la espera (si no, Room falla al abrir).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migracion-test.db"

    @Before
    fun clean() { context.deleteDatabase(name) }

    @After
    fun tearDown() { context.deleteDatabase(name) }

    private fun open() = Room.databaseBuilder(context, FinanzasDatabase::class.java, name)
        .allowMainThreadQueries()
        .addMigrations(*Migrations.ALL)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = DefaultData.insertInto(db, 0L)
        })
        .build()

    @Test
    fun migrarDe1a2ConservaLosDatosYCreaElChat() = runTest {
        // 1. Base creada con el esquema actual y luego devuelta al estado de la versión 1.
        open().apply { openHelper.writableDatabase; close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE chat_messages")
            raw.execSQL("DROP TABLE notification_samples")
            raw.version = 1
        }

        // 2. Al abrir de nuevo se ejecuta MIGRATION_1_2 y Room valida el esquema.
        val db = open()
        try {
            val accounts = db.accountDao().observeActiveWithBalance().first()
            assertEquals(DefaultData.accounts.size, accounts.size)
            db.chatDao().insert(ChatMessageEntity(role = "user", text = "hola", createdAtEpochMs = 1L))
            assertEquals(listOf("hola"), db.chatDao().observeLatest(10).first().map { it.text })
        } finally {
            db.close()
        }
    }

    @Test
    fun migrarDe2a3CreaLasMuestras() = runTest {
        open().apply { openHelper.writableDatabase; close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE notification_samples")
            raw.version = 2
        }
        val db = open()
        try {
            db.notificationSampleDao().insert(
                com.finanzaspersonales.core.database.entity.NotificationSampleEntity(
                    bank = "NEQUI", maskedText = "Pagaste \$5.000", result = "Pago", postedAtEpochMs = 1L,
                ),
            )
            assertEquals(1, db.notificationSampleDao().observeAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun observeLatestDevuelveLosUltimosEnOrden() = runTest {
        val db = open()
        try {
            (1..5).forEach { db.chatDao().insert(ChatMessageEntity(role = "user", text = "m$it", createdAtEpochMs = it.toLong())) }
            assertEquals(listOf("m3", "m4", "m5"), db.chatDao().observeLatest(3).first().map { it.text })
        } finally {
            db.close()
        }
    }
}
