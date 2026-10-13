package com.vigia.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DnsDao {
    @Insert
    suspend fun insertarLote(eventos: List<DnsEventEntity>)

    @Query("SELECT * FROM dns_events WHERE session_id = :sesion ORDER BY ts DESC, id DESC LIMIT :limite")
    fun observar(sesion: Long, limite: Int): Flow<List<DnsEventEntity>>

    @Query("SELECT COUNT(*) FROM dns_events WHERE session_id = :sesion")
    suspend fun contar(sesion: Long): Int

    @Query("SELECT COUNT(DISTINCT dominio) FROM dns_events WHERE session_id = :sesion")
    suspend fun contarDominios(sesion: Long): Int

    @Query("SELECT COUNT(DISTINCT app) FROM dns_events WHERE session_id = :sesion")
    suspend fun contarApps(sesion: Long): Int

    @Query("DELETE FROM dns_events WHERE session_id = :sesion")
    suspend fun borrarDeSesion(sesion: Long)
}
