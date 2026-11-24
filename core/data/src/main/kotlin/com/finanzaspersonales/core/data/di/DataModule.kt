package com.finanzaspersonales.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    /** Reloj inyectable para poder probar cálculos que dependen de la fecha. */
    @Provides fun clock(): Clock = Clock.systemUTC()
}
