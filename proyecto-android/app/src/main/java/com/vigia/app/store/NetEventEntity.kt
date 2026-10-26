package com.vigia.app.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Tabla preparada para la Fase 2B (metadatos de conexiones TCP/UDP y SNI). Todavía no se escribe. */
@Entity(
    tableName = "net_events",
    indices = [Index(value = ["session_id"]), Index(value = ["ts"])]
)
data class NetEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val ts: Long,
    val uid: Int,
    val app: String,
    val ip: String,
    val puerto: Int,
    val proto: String,
    val sni: String,
    @ColumnInfo(name = "bytes_in") val bytesIn: Long,
    @ColumnInfo(name = "bytes_out") val bytesOut: Long
)
