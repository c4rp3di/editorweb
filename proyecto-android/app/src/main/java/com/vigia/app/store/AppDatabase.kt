package com.vigia.app.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        SessionEntity::class,
        DnsEventEntity::class,
        NetEventEntity::class,
        SysEventEntity::class,
        SensorEventEntity::class,
        A11yEventEntity::class,
        InventarioSnapshotEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun dnsDao(): DnsDao

    abstract fun eventosDao(): EventosDao

    abstract fun inventarioDao(): InventarioDao

    companion object {
        @Volatile
        private var instancia: AppDatabase? = null

        /** Fase 3: añade las tablas de eventos del sistema, sensores, accesibilidad e inventario. */
        private val MIGRACION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sys_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, `ts` INTEGER NOT NULL, `tipo` TEXT NOT NULL, `payload` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sys_events_session_id` ON `sys_events` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sys_events_ts` ON `sys_events` (`ts`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sensor_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, `ts` INTEGER NOT NULL, `uid` INTEGER NOT NULL, " +
                        "`app` TEXT NOT NULL, `sensor` TEXT NOT NULL, `modo` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sensor_events_session_id` ON `sensor_events` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sensor_events_ts` ON `sensor_events` (`ts`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `a11y_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, `ts` INTEGER NOT NULL, `uid` INTEGER NOT NULL, " +
                        "`app` TEXT NOT NULL, `tipo_evento` TEXT NOT NULL, `detalle` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_a11y_events_session_id` ON `a11y_events` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_a11y_events_ts` ON `a11y_events` (`ts`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inventory_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, `ts` INTEGER NOT NULL, `payload` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inventory_snapshots_session_id` ON `inventory_snapshots` (`session_id`)"
                )
            }
        }

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
                .addMigrations(MIGRACION_2_3)
                // La versión 1 solo guardaba sesiones de prueba: se recrea el esquema sin migrar.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
        }
    }
}
