package com.vigia.app.store

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inicio: Long,
    val fin: Long? = null,
    val estado: String,
    val configuracion: String = "{}",
    val notas: String = ""
)
