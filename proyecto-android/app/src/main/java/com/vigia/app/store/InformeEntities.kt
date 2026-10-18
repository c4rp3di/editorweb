package com.vigia.app.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Informe final de una sesión (sobre firmado en JSON). Se conserva aunque se borren los datos crudos. */
@Entity(
    tableName = "informes",
    indices = [Index(value = ["session_id"], unique = true)]
)
data class InformeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val ts: Long,
    val contenido: String
)

/** Alertas ya enviadas por cada canal, para no repetir el mismo hallazgo. */
@Entity(
    tableName = "alert_log",
    primaryKeys = ["clave", "canal"],
    indices = [Index(value = ["session_id"])]
)
data class AlertLogEntity(
    val clave: String,
    val canal: String,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val ts: Long
)

data class AppConteo(val app: String, val n: Int)

data class TipoConteo(val tipo: String, val n: Int)
