package com.vigia.app.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "findings",
    indices = [Index(value = ["session_id"])]
)
data class FindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val ts: Long,
    val severidad: String,
    val modulo: String,
    val regla: String,
    val app: String,
    val titulo: String,
    val descripcion: String,
    @ColumnInfo(name = "evidencia_ids") val evidenciaIds: String,
    val confianza: Int
)
