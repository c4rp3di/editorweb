package com.carpe.panoptes.core.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "permission",
    indices = [Index("analysisId")]
)
data class PermissionEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: String,
    val name: String,
    val protectionLevel: String,
    val description: String?
)
