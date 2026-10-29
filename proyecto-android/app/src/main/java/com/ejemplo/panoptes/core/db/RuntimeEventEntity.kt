package com.carpe.panoptes.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Poblada a partir de F6 (frida-server, análisis dinámico con root). Vacía hasta entonces. */
@Entity(
    tableName = "runtime_event",
    indices = [Index("analysisId")]
)
data class RuntimeEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: String,
    val timestamp: Long,
    val type: String,
    val payload: String = "{}"
)
