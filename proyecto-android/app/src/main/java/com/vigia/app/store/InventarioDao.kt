package com.vigia.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface InventarioDao {
    @Insert
    suspend fun insertar(snapshot: InventarioSnapshotEntity): Long

    @Query("SELECT * FROM inventory_snapshots WHERE session_id = :sesion ORDER BY ts DESC LIMIT 1")
    suspend fun ultimo(sesion: Long): InventarioSnapshotEntity?

    @Query("SELECT COUNT(*) FROM inventory_snapshots WHERE session_id = :sesion")
    suspend fun contar(sesion: Long): Int

    /** Conserva la primera foto y las 4 más recientes; el resto se descarta para no acumular datos. */
    @Query(
        "DELETE FROM inventory_snapshots WHERE session_id = :sesion " +
            "AND id NOT IN (SELECT id FROM inventory_snapshots WHERE session_id = :sesion ORDER BY ts ASC LIMIT 1) " +
            "AND id NOT IN (SELECT id FROM inventory_snapshots WHERE session_id = :sesion ORDER BY ts DESC LIMIT 4)"
    )
    suspend fun podar(sesion: Long)

    @Query("DELETE FROM inventory_snapshots WHERE session_id = :sesion")
    suspend fun borrar(sesion: Long)
}
