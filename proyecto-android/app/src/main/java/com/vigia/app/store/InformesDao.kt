package com.vigia.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InformesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarInforme(informe: InformeEntity)

    @Query("SELECT * FROM informes WHERE session_id = :sesion LIMIT 1")
    suspend fun informe(sesion: Long): InformeEntity?

    @Query("DELETE FROM informes WHERE session_id = :sesion")
    suspend fun borrarInforme(sesion: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun registrarAlertas(lista: List<AlertLogEntity>)

    @Query("SELECT clave FROM alert_log WHERE canal = :canal AND session_id = :sesion")
    suspend fun clavesAlertadas(canal: String, sesion: Long): List<String>

    @Query("DELETE FROM alert_log WHERE session_id = :sesion")
    suspend fun borrarAlertas(sesion: Long)
}
