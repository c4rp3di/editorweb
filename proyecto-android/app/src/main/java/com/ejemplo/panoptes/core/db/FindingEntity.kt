package com.carpe.panoptes.core.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Severity { INFO, LOW, MEDIUM, HIGH }

@Entity(
    tableName = "finding",
    indices = [Index("analysisId")]
)
data class FindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: String,
    val category: String,       // "permission" | "endpoint" | "secret" | "component" ...
    val severity: Severity,
    val description: String,
    val evidence: String = "{}" // JSON
)

@Fts4(contentEntity = FindingEntity::class)
@Entity(tableName = "finding_fts")
data class FindingFts(val description: String)
