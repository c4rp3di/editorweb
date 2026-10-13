package com.vigia.app.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [SessionEntity::class, DnsEventEntity::class, NetEventEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun dnsDao(): DnsDao

    companion object {
        @Volatile
        private var instancia: AppDatabase? = null

        fun obtener(context: Context): AppDatabase {
            val actual = instancia
            if (actual != null) return actual
            return synchronized(this) {
                val doble = instancia
                if (doble != null) {
                    doble
                } else {
                    val nueva = construir(context.applicationContext)
                    instancia = nueva
                    nueva
                }
            }
        }

        private fun construir(contexto: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val clave = KeyVault.obtenerClaveBd(contexto)
            val fabrica = SupportOpenHelperFactory(clave)
            return Room.databaseBuilder(contexto, AppDatabase::class.java, "vigia.db")
                .openHelperFactory(fabrica)
                // La versión 1 solo guardaba sesiones de prueba: se recrea el esquema sin migrar.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
        }
    }
}
