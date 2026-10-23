package com.vigia.app.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "a11y_events",
    indices = [Index(value = ["session_id"]), Index(value = ["ts"])]
)
data class A11yEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val ts: Long,
    val uid: Int,
    val app: String,
    @ColumnInfo(name = "tipo_evento") val tipoEvento: String,
    val detalle: String
)
