package com.luzcontrol.app.domain.repository

import com.luzcontrol.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate

/** Fuente única de verdad para la UI: todo se lee de la base de datos local. */
interface EnergyRepository {
    fun observeSupplies(): Flow<List<Supply>>
    fun observeContract(cups: String): Flow<Contract?>
    fun observeConsumption(cups: String, from: Instant, to: Instant): Flow<List<ConsumptionPoint>>
    fun observePrices(from: Instant, to: Instant): Flow<List<PricePoint>>
    fun observeMaxPower(cups: String, from: LocalDate, to: LocalDate): Flow<List<MaxPowerPoint>>
    fun observeLastSync(): Flow<SyncLog?>
    suspend fun setSupplyAlias(cups: String, alias: String?)
    suspend fun clearAllData()
}

/** Descarga datos remotos y los vuelca en la base local. */
interface SyncRepository {
    suspend fun sync(mode: SyncMode): SyncResult
}

interface SettingsRepository {
    val settings: StateFlow<UserSettings>
    fun update(transform: (UserSettings) -> UserSettings)
}

interface CredentialsRepository {
    val hasDatadisCredentials: StateFlow<Boolean>
    fun getDatadis(): DatadisCredentials?
    fun saveDatadis(credentials: DatadisCredentials)
    fun clearDatadis()
    fun getEsiosToken(): String?
    fun saveEsiosToken(token: String?)
}

/** Valida credenciales contra Datadis sin guardarlas. */
interface CredentialsValidator {
    suspend fun validate(credentials: DatadisCredentials): Result<Int>
}

interface SyncScheduler {
    fun setPeriodicEnabled(enabled: Boolean)
    fun syncNow()
    fun syncOnOpenIfStale(maxAgeHours: Long)
    fun observeSyncing(): Flow<Boolean>
}

interface ExportRepository {
    suspend fun exportCsv(cups: String): Result<ExportedFile>
    suspend fun exportPdf(cups: String): Result<ExportedFile>
}
