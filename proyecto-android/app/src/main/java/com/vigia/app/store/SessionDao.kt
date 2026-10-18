package com.vigia.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insertar(sesion: SessionEntity): Long

    @Update
    suspend fun actualizar(sesion: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun obtener(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE fin IS NULL ORDER BY inicio DESC LIMIT 1")
    suspend fun abierta(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE fin IS NOT NULL ORDER BY inicio DESC LIMIT 1")
    suspend fun ultimaCerrada(): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY inicio DESC")
    fun observarTodas(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE fin IS NOT NULL AND fin < :limite")
    suspend fun cerradasAntesDe(limite: Long): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun borrar(id: Long)
}
