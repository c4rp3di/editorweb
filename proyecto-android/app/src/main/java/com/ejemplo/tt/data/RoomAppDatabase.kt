package com.ejemplo.tt.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

// Entidad, Dao y Database de ejemplo con Room. Adáptalos a lo que necesite
// tu app; nombres distintos de los de "base-datos" (BaseDatos.kt) a
// propósito, para que las dos funcionalidades puedan convivir marcadas a la vez.
@Entity(tableName = "elementos_room")
data class ElementoRoom(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nombre: String,
    val valor: String
)

@Dao
interface ElementoRoomDao {
    @Insert
    suspend fun insertar(elemento: ElementoRoom)

    @Query("SELECT * FROM elementos_room")
    suspend fun listarTodos(): List<ElementoRoom>
}

@Database(entities = [ElementoRoom::class], version = 1, exportSchema = false)
abstract class AppRoomDatabase : RoomDatabase() {
    abstract fun elementoDao(): ElementoRoomDao

    companion object {
        @Volatile private var instancia: AppRoomDatabase? = null

        fun obtener(context: Context): AppRoomDatabase {
            return instancia ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppRoomDatabase::class.java, "room_app.db")
                    .build()
                    .also { instancia = it }
            }
        }
    }
}
