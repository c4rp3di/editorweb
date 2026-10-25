package com.vigia.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventosDao {
    @Insert
    suspend fun insertarSys(eventos: List<SysEventEntity>)

    @Insert
    suspend fun insertarSensor(eventos: List<SensorEventEntity>)

    @Insert
    suspend fun insertarA11y(eventos: List<A11yEventEntity>)

    @Query("SELECT * FROM sys_events WHERE session_id = :sesion ORDER BY ts DESC, id DESC LIMIT :limite")
    fun observarSys(sesion: Long, limite: Int): Flow<List<SysEventEntity>>

    @Query("SELECT * FROM sensor_events WHERE session_id = :sesion ORDER BY ts DESC, id DESC LIMIT :limite")
    fun observarSensor(sesion: Long, limite: Int): Flow<List<SensorEventEntity>>

    @Query("SELECT * FROM a11y_events WHERE session_id = :sesion ORDER BY ts DESC, id DESC LIMIT :limite")
    fun observarA11y(sesion: Long, limite: Int): Flow<List<A11yEventEntity>>

    @Query("SELECT * FROM sys_events WHERE session_id = :sesion ORDER BY ts ASC, id ASC")
    suspend fun todosSys(sesion: Long): List<SysEventEntity>

    @Query("SELECT * FROM sensor_events WHERE session_id = :sesion ORDER BY ts ASC, id ASC")
    suspend fun todosSensor(sesion: Long): List<SensorEventEntity>

    @Query("SELECT * FROM a11y_events WHERE session_id = :sesion ORDER BY ts ASC, id ASC")
    suspend fun todosA11y(sesion: Long): List<A11yEventEntity>

    @Query("SELECT COUNT(*) FROM sys_events WHERE session_id = :sesion")
    suspend fun contarSys(sesion: Long): Int

    @Query("SELECT COUNT(*) FROM sensor_events WHERE session_id = :sesion")
    suspend fun contarSensor(sesion: Long): Int

    @Query("SELECT COUNT(*) FROM a11y_events WHERE session_id = :sesion")
    suspend fun contarA11y(sesion: Long): Int

    @Query("SELECT tipo AS tipo, COUNT(*) AS n FROM sys_events WHERE session_id = :sesion GROUP BY tipo ORDER BY n DESC")
    suspend fun sysPorTipo(sesion: Long): List<TipoConteo>

    @Query("DELETE FROM sys_events WHERE session_id = :sesion")
    suspend fun borrarSys(sesion: Long)

    @Query("DELETE FROM sensor_events WHERE session_id = :sesion")
    suspend fun borrarSensor(sesion: Long)

    @Query("DELETE FROM a11y_events WHERE session_id = :sesion")
    suspend fun borrarA11y(sesion: Long)
}
