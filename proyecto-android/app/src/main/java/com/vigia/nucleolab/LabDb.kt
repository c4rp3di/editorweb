package com.vigia.nucleolab

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vigia.core.seguridad.AlmacenClaves
import com.vigia.core.seguridad.BaseDatosCifrada

@Entity(tableName = "notas")
data class Nota(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val texto: String
)

@Dao
interface NotaDao {
    @Insert
    suspend fun insertar(nota: Nota): Long

    @Query("SELECT COUNT(*) FROM notas")
    suspend fun contar(): Int
}

@Database(entities = [Nota::class], version = 1, exportSchema = false)
abstract class LabDb : RoomDatabase() {

    abstract fun notaDao(): NotaDao

    companion object {
        const val NOMBRE = "lab.db"

        @Volatile
        private var instancia: LabDb? = null

        fun obtener(contexto: Context): LabDb {
            val actual = instancia
            if (actual != null) return actual
            return synchronized(this) {
                val doble = instancia
                if (doble != null) {
                    doble
                } else {
                    val app = contexto.applicationContext
                    val almacen = AlmacenClaves.paraApp(app, "lab")
                    val nueva = Room.databaseBuilder(app, LabDb::class.java, NOMBRE)
                        .openHelperFactory(BaseDatosCifrada.fabrica(almacen, NOMBRE))
                        .build()
                    instancia = nueva
                    nueva
                }
            }
        }
    }
}
