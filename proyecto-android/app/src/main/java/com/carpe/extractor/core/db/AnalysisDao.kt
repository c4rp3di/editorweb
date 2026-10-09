package com.carpe.panoptes.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: AnalysisEntity)

    @Update
    suspend fun update(analysis: AnalysisEntity)

    @Query("SELECT * FROM analysis ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analysis WHERE id = :id")
    suspend fun getById(id: String): AnalysisEntity?

    @Query("SELECT * FROM analysis WHERE packageName = :packageName ORDER BY startedAt DESC")
    suspend fun getByPackage(packageName: String): List<AnalysisEntity>
}
