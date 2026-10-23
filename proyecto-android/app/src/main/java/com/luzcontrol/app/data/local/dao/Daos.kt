package com.luzcontrol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.luzcontrol.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplyDao {
    @Query("SELECT * FROM supply ORDER BY cups")
    fun observeAll(): Flow<List<SupplyEntity>>

    @Query("SELECT * FROM supply ORDER BY cups")
    suspend fun getAll(): List<SupplyEntity>

    @Query("SELECT * FROM supply WHERE cups = :cups")
    suspend fun get(cups: String): SupplyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SupplyEntity>)

    @Query("UPDATE supply SET alias = :alias WHERE cups = :cups")
    suspend fun setAlias(cups: String, alias: String?)

    @Query("DELETE FROM supply")
    suspend fun deleteAll()
}

@Dao
interface ContractDao {
    @Query("SELECT * FROM contract WHERE cups = :cups")
    fun observe(cups: String): Flow<ContractEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ContractEntity)

    @Query("SELECT * FROM contract WHERE cups = :cups")
    suspend fun get(cups: String): ContractEntity?

    @Query("DELETE FROM contract")
    suspend fun deleteAll()
}

@Dao
interface ConsumptionDao {
    @Query("SELECT * FROM consumption WHERE cups = :cups AND datetime >= :from AND datetime < :to ORDER BY datetime")
    fun observe(cups: String, from: Long, to: Long): Flow<List<ConsumptionEntity>>

    @Query("SELECT * FROM consumption WHERE cups = :cups AND datetime >= :from AND datetime < :to ORDER BY datetime")
    suspend fun list(cups: String, from: Long, to: Long): List<ConsumptionEntity>

    @Query("SELECT * FROM consumption WHERE cups = :cups ORDER BY datetime")
    suspend fun listAll(cups: String): List<ConsumptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ConsumptionEntity>)

    @Query("SELECT MAX(datetime) FROM consumption WHERE cups = :cups")
    suspend fun latest(cups: String): Long?

    @Query("DELETE FROM consumption")
    suspend fun deleteAll()
}

@Dao
interface PriceDao {
    @Query("SELECT * FROM price WHERE datetime >= :from AND datetime < :to ORDER BY datetime")
    fun observe(from: Long, to: Long): Flow<List<PriceEntity>>

    @Query("SELECT * FROM price WHERE datetime >= :from AND datetime < :to ORDER BY datetime")
    suspend fun list(from: Long, to: Long): List<PriceEntity>

    @Query("SELECT * FROM price ORDER BY datetime")
    suspend fun listAll(): List<PriceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PriceEntity>)

    @Query("SELECT MAX(datetime) FROM price")
    suspend fun latest(): Long?

    @Query("DELETE FROM price")
    suspend fun deleteAll()
}

@Dao
interface MaxPowerDao {
    @Query("SELECT * FROM max_power WHERE cups = :cups AND date >= :from AND date <= :to ORDER BY date, time")
    fun observe(cups: String, from: String, to: String): Flow<List<MaxPowerEntity>>

    @Query("SELECT * FROM max_power WHERE cups = :cups ORDER BY date, time")
    suspend fun listAll(cups: String): List<MaxPowerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MaxPowerEntity>)

    @Query("DELETE FROM max_power")
    suspend fun deleteAll()
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT 1")
    fun observeLast(): Flow<SyncLogEntity?>

    @Insert
    suspend fun insert(item: SyncLogEntity)

    /** Conserva solo los últimos registros para que la tabla no crezca sin límite. */
    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY id DESC LIMIT 200)")
    suspend fun trim()

    @Query("DELETE FROM sync_log")
    suspend fun deleteAll()
}
