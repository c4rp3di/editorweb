package com.luzcontrol.app.di

import android.content.Context
import androidx.room.Room
import com.luzcontrol.app.data.export.ExportRepositoryImpl
import com.luzcontrol.app.data.local.AppDatabase
import com.luzcontrol.app.data.remote.NetworkConfig
import com.luzcontrol.app.data.remote.api.DatadisApi
import com.luzcontrol.app.data.remote.api.EsiosApi
import com.luzcontrol.app.data.remote.api.ReeApi
import com.luzcontrol.app.data.repository.CredentialsValidatorImpl
import com.luzcontrol.app.data.repository.EnergyRepositoryImpl
import com.luzcontrol.app.data.repository.SettingsRepositoryImpl
import com.luzcontrol.app.data.repository.SyncRepositoryImpl
import com.luzcontrol.app.data.security.CredentialsRepositoryImpl
import com.luzcontrol.app.data.sync.WorkManagerSyncScheduler
import com.luzcontrol.app.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        // Los datos son una caché reconstruible desde Datadis/ESIOS, por eso se permite recrear la base.
        Room.databaseBuilder(context, AppDatabase::class.java, "luzcontrol.db")
            .fallbackToDestructiveMigration()
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        // Sin interceptor de logging a propósito: las peticiones llevan DNI/contraseña/token.
        val pinner = CertificatePinner.Builder()
        var anyPin = false
        for ((host, pins) in NetworkConfig.PINS) {
            for (pin in pins) { pinner.add(host, pin); anyPin = true }
        }
        if (anyPin) builder.certificatePinner(pinner.build())
        return builder.build()
    }

    private fun retrofit(base: String, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideDatadisApi(client: OkHttpClient, json: Json): DatadisApi =
        retrofit(NetworkConfig.DATADIS_BASE, client, json).create(DatadisApi::class.java)

    @Provides
    @Singleton
    fun provideEsiosApi(client: OkHttpClient, json: Json): EsiosApi =
        retrofit(NetworkConfig.ESIOS_BASE, client, json).create(EsiosApi::class.java)

    @Provides
    @Singleton
    fun provideReeApi(client: OkHttpClient, json: Json): ReeApi =
        retrofit(NetworkConfig.REE_BASE, client, json).create(ReeApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun energy(impl: EnergyRepositoryImpl): EnergyRepository
    @Binds @Singleton abstract fun sync(impl: SyncRepositoryImpl): SyncRepository
    @Binds @Singleton abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds @Singleton abstract fun credentials(impl: CredentialsRepositoryImpl): CredentialsRepository
    @Binds @Singleton abstract fun scheduler(impl: WorkManagerSyncScheduler): SyncScheduler
    @Binds @Singleton abstract fun export(impl: ExportRepositoryImpl): ExportRepository
    @Binds abstract fun validator(impl: CredentialsValidatorImpl): CredentialsValidator
}
