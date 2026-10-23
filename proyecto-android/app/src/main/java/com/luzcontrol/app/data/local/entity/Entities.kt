package com.luzcontrol.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "supply")
data class SupplyEntity(
    @PrimaryKey val cups: String,
    val distributorCode: String,
    val distributor: String,
    val address: String,
    val municipality: String,
    val province: String,
    val pointType: Int,
    val alias: String?,
    val isActive: Boolean
)

@Entity(tableName = "contract")
data class ContractEntity(
    @PrimaryKey val cups: String,
    val contractedPowerP1: Double,
    val contractedPowerP2: Double,
    val tariffType: String,
    val marketer: String,
    val startDate: String?,
    val timeDiscrimination: String?
)

/** Clave primaria compuesta (cups, datetime). [datetime] = inicio del intervalo en epoch segundos UTC. */
@Entity(
    tableName = "consumption",
    primaryKeys = ["cups", "datetime"],
    indices = [Index("datetime")]
)
data class ConsumptionEntity(
    val cups: String,
    val datetime: Long,
    val consumptionKwh: Double,
    val obtainMethod: String,
    val surplusKwh: Double
)

@Entity(tableName = "price")
data class PriceEntity(
    @PrimaryKey val datetime: Long,
    val pricePvpcEurKwh: Double,
    val priceSpotEurKwh: Double?,
    val priceTramo: Int
)

@Entity(
    tableName = "max_power",
    primaryKeys = ["cups", "date", "time"]
)
data class MaxPowerEntity(
    val cups: String,
    val date: String, // ISO yyyy-MM-dd
    val time: String,
    val maxPowerKw: Double,
    val period: Int
)

@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val source: String,
    val status: String,
    val errorMessage: String?
)
