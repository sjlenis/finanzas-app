package com.finanzaspersonales.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore(name = "preferences")

/** Preferencias de uso que no son datos financieros (se guardan en el teléfono). */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val defaultAccountKey = longPreferencesKey("default_account_id")

    /** Cuenta que usa el asistente cuando el mensaje no dice cuál. Null = la primera activa. */
    val defaultAccountId: Flow<Long?> = context.preferencesStore.data.map { it[defaultAccountKey] }

    suspend fun setDefaultAccountId(id: Long) {
        context.preferencesStore.edit { it[defaultAccountKey] = id }
    }
}
