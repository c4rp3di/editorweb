package com.vigia.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class DestinoBytes(val ip: String, val salida: Long, val entrada: Long)

data class AppBytes(val app: String, val n: Long)

@Dao
interface NetDao {
    @Insert
    suspend fun insertarLote(eventos: List<NetEventEntity>)

    @Query("SELECT * FROM net_events WHERE session_id = :sesion ORDER BY ts ASC, id ASC")
    suspend fun todos(sesion: Long): List<NetEventEntity>

    @Query("SELECT * FROM net_events WHERE session_id = :sesion ORDER BY ts DESC, id DESC LIMIT :limite")
    fun observar(sesion: Long, limite: Int): Flow<List<NetEventEntity>>

    @Query("SELECT COUNT(*) FROM net_events WHERE session_id = :sesion")
    suspend fun contar(sesion: Long): Int

    @Query("SELECT COUNT(DISTINCT ip) FROM net_events WHERE session_id = :sesion")
    suspend fun contarDestinos(sesion: Long): Int

    @Query("SELECT COALESCE(SUM(bytes_in + bytes_out), 0) FROM net_events WHERE session_id = :sesion")
    suspend fun sumaBytes(sesion: Long): Long

    @Query(
        "SELECT ip AS ip, SUM(bytes_out) AS salida, SUM(bytes_in) AS entrada FROM net_events " +
            "WHERE session_id = :sesion GROUP BY ip ORDER BY salida DESC LIMIT :limite"
    )
    suspend fun topDestinos(sesion: Long, limite: Int): List<DestinoBytes>

    @Query(
        "SELECT app AS app, SUM(bytes_in + bytes_out) AS n FROM net_events " +
            "WHERE session_id = :sesion GROUP BY app ORDER BY n DESC LIMIT :limite"
    )
    suspend fun topAppsBytes(sesion: Long, limite: Int): List<AppBytes>

    @Query("DELETE FROM net_events WHERE session_id = :sesion")
    suspend fun borrarDeSesion(sesion: Long)
}
