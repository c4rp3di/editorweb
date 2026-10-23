package com.vigia.app.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Entrada del Escudo: un dominio (incluye sus subdominios y el nombre de servidor TLS) o una IP a bloquear. */
@Entity(
    tableName = "bloqueos",
    indices = [Index(value = ["tipo", "valor"], unique = true)]
)
data class BloqueoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tipo: String,
    val valor: String,
    val creado: Long,
    val nota: String
)

@Dao
interface BloqueosDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(bloqueo: BloqueoEntity): Long

    @Query("SELECT * FROM bloqueos ORDER BY creado DESC, id DESC")
    fun observar(): Flow<List<BloqueoEntity>>

    @Query("SELECT * FROM bloqueos")
    suspend fun todos(): List<BloqueoEntity>

    @Query("DELETE FROM bloqueos WHERE id = :id")
    suspend fun borrar(id: Long)
}
