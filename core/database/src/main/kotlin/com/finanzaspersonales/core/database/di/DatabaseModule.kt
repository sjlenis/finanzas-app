package com.finanzaspersonales.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.finanzaspersonales.core.database.DefaultData
import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.database.Migrations
import com.finanzaspersonales.core.database.dao.AccountDao
import com.finanzaspersonales.core.database.dao.CategoryDao
import com.finanzaspersonales.core.database.dao.TransactionDao
import com.finanzaspersonales.core.security.DatabaseKeyProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DatabaseKeyProvider,
    ): FinanzasDatabase {
        System.loadLibrary("sqlcipher")
        // La clave se genera una vez y queda protegida por Android Keystore.
        val factory = SupportOpenHelperFactory(keyProvider.getOrCreatePassphrase())
        return Room.databaseBuilder(context, FinanzasDatabase::class.java, FinanzasDatabase.FILE_NAME)
            .openHelperFactory(factory)
            .addMigrations(*Migrations.ALL)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    DefaultData.insertInto(db, System.currentTimeMillis())
                }
            })
            .build()
    }

    @Provides fun accountDao(db: FinanzasDatabase): AccountDao = db.accountDao()
    @Provides fun categoryDao(db: FinanzasDatabase): CategoryDao = db.categoryDao()
    @Provides fun transactionDao(db: FinanzasDatabase): TransactionDao = db.transactionDao()
}
