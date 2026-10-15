package com.carpe.panoptes.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun statusToString(status: AnalysisStatus): String = status.name

    @TypeConverter
    fun stringToStatus(value: String): AnalysisStatus = AnalysisStatus.valueOf(value)

    @TypeConverter
    fun componentTypeToString(type: ComponentType): String = type.name

    @TypeConverter
    fun stringToComponentType(value: String): ComponentType = ComponentType.valueOf(value)

    @TypeConverter
    fun severityToString(severity: Severity): String = severity.name

    @TypeConverter
    fun stringToSeverity(value: String): Severity = Severity.valueOf(value)
}

@Database(
    entities = [
        AnalysisEntity::class,
        PermissionEntity::class,
        ComponentEntity::class,
        EndpointEntity::class, EndpointFts::class,
        SecretEntity::class, SecretFts::class,
        NativeLibEntity::class,
        RuntimeEventEntity::class,
        FindingEntity::class, FindingFts::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class PanoptesDatabase : RoomDatabase() {
    abstract fun analysisDao(): AnalysisDao
    abstract fun resultsDao(): ResultsDao

    companion object {
        fun build(context: Context): PanoptesDatabase =
            Room.databaseBuilder(context.applicationContext, PanoptesDatabase::class.java, "panoptes.db")
                // F0→F1 todavía no tiene usuarios reales con datos que conservar;
                // a partir de aquí (release estable) esto debe sustituirse por
                // Migrations explícitas en lugar de destruir y recrear.
                .fallbackToDestructiveMigration()
                .build()
    }
}
