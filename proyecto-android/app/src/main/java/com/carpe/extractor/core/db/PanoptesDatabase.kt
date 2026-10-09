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
}

@Database(
    entities = [AnalysisEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class PanoptesDatabase : RoomDatabase() {
    abstract fun analysisDao(): AnalysisDao

    companion object {
        fun build(context: Context): PanoptesDatabase =
            Room.databaseBuilder(context.applicationContext, PanoptesDatabase::class.java, "panoptes.db")
                .build()
    }
}
