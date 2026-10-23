package com.luzcontrol.app.data.repository

import com.luzcontrol.app.data.local.AppDatabase
import com.luzcontrol.app.data.local.entity.PriceEntity
import com.luzcontrol.app.data.local.entity.SyncLogEntity
import com.luzcontrol.app.data.mapper.toEntities
import com.luzcontrol.app.data.mapper.toEntity
import com.luzcontrol.app.data.remote.DatadisClient
import com.luzcontrol.app.data.remote.PriceRemoteSource
import com.luzcontrol.app.domain.model.*
import com.luzcontrol.app.domain.repository.CredentialsRepository
import com.luzcontrol.app.domain.repository.SettingsRepository
import com.luzcontrol.app.domain.repository.SyncRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sincronización offline-first: descarga de Datadis (suministros, contrato, consumo, maxímetro) y de
 * ESIOS/REE (precios) y vuelca todo en Room. Cada fuente se registra en SyncLog.
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val datadis: DatadisClient,
    private val prices: PriceRemoteSource,
    private val credentials: CredentialsRepository,
    private val settings: SettingsRepository
) : SyncRepository {

    private val mutex = Mutex()
    private val monthFormat = DateTimeFormatter.ofPattern("yyyy/MM")
    private val zone = TariffCalendar.ZONE

    override suspend fun sync(mode: SyncMode): SyncResult = mutex.withLock {
        val errors = ArrayList<AppError>()
        var okSources = 0
        var totalSources = 0

        // 1) Precios (no requieren credenciales)
        totalSources++
        runSource("PRECIOS", errors) { syncPrices() }?.let { okSources++ }

        // 2) Datadis (solo si hay credenciales)
        if (credentials.getDatadis() == null) {
            errors += AppError.NoCredentials
            log("DATADIS", false, AppError.NoCredentials.userMessage())
        } else {
            totalSources++
            runSource("DATADIS", errors) { syncDatadis() }?.let { okSources++ }
        }

        val fatal = errors.filter { it !is AppError.NoCredentials }
        val success = okSources == totalSources && fatal.isEmpty()
        val partial = okSources > 0 && !success
        val message = when {
            success && errors.isEmpty() -> "Sincronización completada"
            success -> "Precios actualizados. ${AppError.NoCredentials.userMessage()}"
            partial -> "Sincronización parcial: " + fatal.firstOrNull()?.userMessage().orEmpty()
            else -> fatal.firstOrNull()?.userMessage() ?: "No se pudo sincronizar"
        }
        SyncResult(success = success, partial = partial, errors = errors, message = message)
    }

    private suspend fun <T> runSource(source: String, errors: MutableList<AppError>, block: suspend () -> T): T? {
        return try {
            val r = block()
            log(source, true, null)
            r
        } catch (e: AppError) {
            errors += e
            log(source, false, e.userMessage())
            null
        } catch (e: Exception) {
            val err = AppError.Unknown(e)
            errors += err
            log(source, false, err.userMessage())
            null
        }
    }

    private suspend fun log(source: String, ok: Boolean, message: String?) {
        db.syncLogDao().insert(
            SyncLogEntity(
                timestamp = Instant.now().epochSecond,
                source = source,
                status = if (ok) "OK" else "ERROR",
                errorMessage = message
            )
        )
        db.syncLogDao().trim()
    }

    // ---------------------------------------------------------------- precios

    private suspend fun syncPrices() {
        val today = LocalDate.now(zone)
        val latest = db.priceDao().latest()
        val from = if (latest == null) today.minusDays(30)
        else Instant.ofEpochSecond(latest).atZone(zone).toLocalDate().minusDays(1)
        val to = today.plusDays(1)
        val fetched = prices.fetch(from, to)
        if (fetched.isNotEmpty()) {
            db.priceDao().upsertAll(fetched.map {
                PriceEntity(
                    datetime = it.start.epochSecond,
                    pricePvpcEurKwh = it.pvpcEurKwh,
                    priceSpotEurKwh = it.spotEurKwh,
                    priceTramo = TariffCalendar.periodAt(it.start).code
                )
            })
        }
    }

    // ---------------------------------------------------------------- Datadis

    private suspend fun syncDatadis() {
        // Suministros (conserva alias y selección del usuario)
        val remote = datadis.supplies()
        val existing = db.supplyDao().getAll().associateBy { it.cups }
        if (remote.isNotEmpty()) {
            db.supplyDao().upsertAll(remote.mapIndexed { i, dto ->
                dto.toEntity(existing[dto.cups]?.alias, existing[dto.cups]?.isActive ?: (i == 0))
            })
        }
        val supplies = db.supplyDao().getAll()
        if (supplies.isEmpty()) return

        val active = settings.settings.value.activeCups?.takeIf { c -> supplies.any { it.cups == c } }
            ?: supplies.first().cups
        if (settings.settings.value.activeCups == null) {
            settings.update { it.copy(activeCups = active) }
        }
        // Se sincronizan todos los suministros del usuario; el activo primero.
        val ordered = supplies.sortedByDescending { it.cups == active }
        for ((index, supply) in ordered.withIndex()) {
            if (index > 0) delay(1_500)
            syncSupply(supply.cups, supply.distributorCode, supply.pointType)
        }
    }

    private suspend fun syncSupply(cups: String, distributorCode: String, pointType: Int) {
        // Contrato
        val contract = datadis.contract(cups, distributorCode)
        contract.firstOrNull()?.let { db.contractDao().upsert(it.toEntity(cups)) }
        delay(1_000)

        // Consumo: primera vez 12 meses; después desde 7 días antes de la última lectura
        val now = YearMonth.now(zone)
        val latest = db.consumptionDao().latest(cups)
        val firstMonth = if (latest == null) now.minusMonths(INITIAL_MONTHS - 1L)
        else YearMonth.from(Instant.ofEpochSecond(latest).atZone(zone).minusDays(7))
        var month = firstMonth
        while (!month.isAfter(now)) {
            val rows = try {
                datadis.consumption(cups, distributorCode, pointType, month.format(monthFormat))
            } catch (e: AppError.Server) {
                // Un mes sin datos o con error puntual no detiene el resto
                emptyList()
            }
            if (rows.isNotEmpty()) db.consumptionDao().upsertAll(rows.toEntities(cups, zone, 60))
            month = month.plusMonths(1)
            if (!month.isAfter(now)) delay(1_500)
        }

        // Maxímetro: últimos meses
        val maxMonths = if (latest == null) INITIAL_MONTHS else 2
        var m = now.minusMonths(maxMonths - 1L)
        while (!m.isAfter(now)) {
            val rows = try {
                datadis.maxPower(cups, distributorCode, m.format(monthFormat))
            } catch (e: AppError.Server) {
                emptyList()
            }
            val entities = rows.mapNotNull { it.toEntity(cups) }
            if (entities.isNotEmpty()) db.maxPowerDao().upsertAll(entities)
            m = m.plusMonths(1)
            if (!m.isAfter(now)) delay(1_000)
        }
    }

    private companion object {
        const val INITIAL_MONTHS = 12L
    }
}
