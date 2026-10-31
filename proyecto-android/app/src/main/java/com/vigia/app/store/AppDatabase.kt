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
        InventarioSnapshotEntity::class,
        IocEntity::class,
        FindingEntity::class,
        BaselineSampleEntity::class,
        BaselineEntity::class,
        DominioVistoEntity::class,
        InformeEntity::class,
        AlertLogEntity::class,
        BloqueoEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun dnsDao(): DnsDao

    abstract fun eventosDao(): EventosDao

    abstract fun inventarioDao(): InventarioDao

    abstract fun analisisDao(): AnalisisDao

    abstract fun informesDao(): InformesDao

    abstract fun netDao(): NetDao

    abstract fun bloqueosDao(): BloqueosDao

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

        /** Fase 4: indicadores, hallazgos y línea base. */
        private val MIGRACION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `iocs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tipo` TEXT NOT NULL, `valor` TEXT NOT NULL, `fuente` TEXT NOT NULL, " +
                        "`fecha_alta` INTEGER NOT NULL, `fecha_baja` INTEGER)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_iocs_tipo_valor` ON `iocs` (`tipo`, `valor`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_iocs_fuente` ON `iocs` (`fuente`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `findings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, `ts` INTEGER NOT NULL, `severidad` TEXT NOT NULL, " +
                        "`modulo` TEXT NOT NULL, `regla` TEXT NOT NULL, `app` TEXT NOT NULL, `titulo` TEXT NOT NULL, " +
                        "`descripcion` TEXT NOT NULL, `evidencia_ids` TEXT NOT NULL, `confianza` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_findings_session_id` ON `findings` (`session_id`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `baseline_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, `metrica` TEXT NOT NULL, `dia` INTEGER NOT NULL, " +
                        "`hora` INTEGER NOT NULL, `valor` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_baseline_samples_session_id` ON `baseline_samples` (`session_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_baseline_samples_metrica_hora` ON `baseline_samples` (`metrica`, `hora`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `baseline` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`metrica` TEXT NOT NULL, `ventana` TEXT NOT NULL, `media` REAL NOT NULL, " +
                        "`desviacion` REAL NOT NULL, `p5` REAL NOT NULL, `p50` REAL NOT NULL, `p95` REAL NOT NULL, " +
                        "`muestras` INTEGER NOT NULL, `actualizado_ts` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_baseline_metrica_ventana` ON `baseline` (`metrica`, `ventana`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dominios_vistos` (`dominio` TEXT NOT NULL, " +
                        "`primera_vez` INTEGER NOT NULL, PRIMARY KEY(`dominio`))"
                )
            }
        }

        /** Fase 5: informes finales y registro de alertas enviadas. */
        private val MIGRACION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `informes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, `ts` INTEGER NOT NULL, `contenido` TEXT NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_informes_session_id` ON `informes` (`session_id`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `alert_log` (`clave` TEXT NOT NULL, `canal` TEXT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, `ts` INTEGER NOT NULL, PRIMARY KEY(`clave`, `canal`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_log_session_id` ON `alert_log` (`session_id`)")
            }
        }

        /** Fase 7.1: lista de bloqueos del Escudo. */
        private val MIGRACION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bloqueos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tipo` TEXT NOT NULL, `valor` TEXT NOT NULL, `creado` INTEGER NOT NULL, `nota` TEXT NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bloqueos_tipo_valor` ON `bloqueos` (`tipo`, `valor`)")
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
                .addMigrations(MIGRACION_2_3, MIGRACION_3_4, MIGRACION_4_5, MIGRACION_5_6)
                // La versión 1 solo guardaba sesiones de prueba: se recrea el esquema sin migrar.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
        }
    }
}
