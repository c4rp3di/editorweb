package com.vigia.app.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sys_events",
    indices = [Index(value = ["session_id"]), Index(value = ["ts"])]
)
data class SysEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val ts: Long,
    val tipo: String,
    val payload: String
)
