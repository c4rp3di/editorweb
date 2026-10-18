package com.carpe.panoptes.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Poblada a partir de F4 (radare2) — en F1 solo se listan los .so presentes, sin analizar. */
@Entity(
    tableName = "native_lib",
    indices = [Index("analysisId")]
)
data class NativeLibEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: String,
    val name: String,
    val arch: String,
    val imports: String = "[]",
    val exports: String = "[]",
    val strings: String = "[]"
)
