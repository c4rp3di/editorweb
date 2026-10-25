package com.carpe.panoptes.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ComponentType { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

@Entity(
    tableName = "component",
    indices = [Index("analysisId")]
)
data class ComponentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: String,
    val type: ComponentType,
    val name: String,
    val exported: Boolean,
    val intentFilters: String = "[]" // JSON
)
