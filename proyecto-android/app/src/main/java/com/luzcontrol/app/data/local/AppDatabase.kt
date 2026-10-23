package com.luzcontrol.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.luzcontrol.app.data.local.dao.*
import com.luzcontrol.app.data.local.entity.*

@Database(
    entities = [
        SupplyEntity::class,
        ContractEntity::class,
        ConsumptionEntity::class,
        PriceEntity::class,
        MaxPowerEntity::class,
        SyncLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun supplyDao(): SupplyDao
    abstract fun contractDao(): ContractDao
    abstract fun consumptionDao(): ConsumptionDao
    abstract fun priceDao(): PriceDao
    abstract fun maxPowerDao(): MaxPowerDao
    abstract fun syncLogDao(): SyncLogDao
}
