package com.finanzaspersonales.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
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
class UserPreferences internal constructor(
    private val store: DataStore<Preferences>,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.preferencesStore)

    private val defaultAccountKey = longPreferencesKey("default_account_id")

    /** Cuenta que usa el asistente cuando el mensaje no dice cuál. Null = la primera activa. */
    val defaultAccountId: Flow<Long?> = store.data.map { it[defaultAccountKey] }

    suspend fun setDefaultAccountId(id: Long) {
        store.edit { it[defaultAccountKey] = id }
    }

    private val detectionKey = booleanPreferencesKey("notification_detection_enabled")
    private val samplesKey = booleanPreferencesKey("notification_samples_enabled")

    /** Leer notificaciones de Nequi y Bancolombia. Apagado hasta que lo actives. */
    val notificationDetectionEnabled: Flow<Boolean> = store.data.map { it[detectionKey] ?: false }

    /** Guardar muestras enmascaradas para mejorar el lector. Apagado por defecto. */
    val notificationSamplesEnabled: Flow<Boolean> = store.data.map { it[samplesKey] ?: false }

    suspend fun setNotificationDetection(enabled: Boolean) {
        store.edit { it[detectionKey] = enabled }
    }

    suspend fun setNotificationSamples(enabled: Boolean) {
        store.edit { it[samplesKey] = enabled }
    }

    private val usdRateKey = longPreferencesKey("usd_rate_centavos")

    /**
     * Pesos por 1 dólar (en centavos) para estimar pagos fijos en dólares. La escribes tú
     * o se ajusta con lo que realmente te cobraron; la app no la consulta en internet.
     */
    val usdRate: Flow<com.finanzaspersonales.core.model.Money?> = store.data.map { prefs ->
        prefs[usdRateKey]?.takeIf { it > 0 }?.let { com.finanzaspersonales.core.model.Money(it) }
    }

    suspend fun setUsdRate(rate: com.finanzaspersonales.core.model.Money) {
        require(rate.centavos > 0) { "La tasa debe ser mayor que cero" }
        store.edit { it[usdRateKey] = rate.centavos }
    }
}
