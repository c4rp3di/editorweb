package com.luzcontrol.app.data.repository

import com.luzcontrol.app.data.local.AppDatabase
import com.luzcontrol.app.data.mapper.toDomain
import com.luzcontrol.app.domain.model.*
import com.luzcontrol.app.domain.repository.EnergyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnergyRepositoryImpl @Inject constructor(
    private val db: AppDatabase
) : EnergyRepository {

    override fun observeSupplies(): Flow<List<Supply>> =
        db.supplyDao().observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeContract(cups: String): Flow<Contract?> =
        db.contractDao().observe(cups).map { it?.toDomain() }

    override fun observeConsumption(cups: String, from: Instant, to: Instant): Flow<List<ConsumptionPoint>> =
        db.consumptionDao().observe(cups, from.epochSecond, to.epochSecond).map { l -> l.map { it.toDomain() } }

    override fun observePrices(from: Instant, to: Instant): Flow<List<PricePoint>> =
        db.priceDao().observe(from.epochSecond, to.epochSecond).map { l -> l.map { it.toDomain() } }

    override fun observeMaxPower(cups: String, from: LocalDate, to: LocalDate): Flow<List<MaxPowerPoint>> =
        db.maxPowerDao().observe(cups, from.toString(), to.toString()).map { l -> l.map { it.toDomain() } }

    override fun observeLastSync(): Flow<SyncLog?> =
        db.syncLogDao().observeLast().map { it?.toDomain() }

    override suspend fun setSupplyAlias(cups: String, alias: String?) {
        db.supplyDao().setAlias(cups, alias?.trim()?.takeIf { it.isNotEmpty() })
    }

    override suspend fun clearAllData() {
        db.supplyDao().deleteAll()
        db.contractDao().deleteAll()
        db.consumptionDao().deleteAll()
        db.priceDao().deleteAll()
        db.maxPowerDao().deleteAll()
        db.syncLogDao().deleteAll()
    }
}
