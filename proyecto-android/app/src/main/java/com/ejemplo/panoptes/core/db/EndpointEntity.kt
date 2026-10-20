package com.carpe.panoptes.core.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "endpoint",
    indices = [Index("analysisId")]
)
data class EndpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: String,
    val url: String,
    val method: String? = null,
    val source: String,        // "dex" | "resources" | "manifest"
    val confidence: Float,
    val calledInRuntime: Boolean = false // F6 (dinámico), siempre false hasta entonces
)

/** F1: FTS4 sobre endpoint.url para búsqueda global instantánea (§7 del plan). */
@Fts4(contentEntity = EndpointEntity::class)
@Entity(tableName = "endpoint_fts")
data class EndpointFts(val url: String)
