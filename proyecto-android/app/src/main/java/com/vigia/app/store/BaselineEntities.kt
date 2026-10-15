package com.vigia.app.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Una cuenta por franja: cuántos eventos de una métrica hubo en una hora concreta de un día. */
@Entity(
    tableName = "baseline_samples",
    indices = [Index(value = ["session_id"]), Index(value = ["metrica", "hora"])]
)
data class BaselineSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val metrica: String,
    val dia: Long,
    val hora: Int,
    val valor: Int
)

/** Estadística resumida (14 días móviles) por métrica y hora del día. */
@Entity(
    tableName = "baseline",
    indices = [Index(value = ["metrica", "ventana"], unique = true)]
)
data class BaselineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metrica: String,
    val ventana: String,
    val media: Double,
    val desviacion: Double,
    val p5: Double,
    val p50: Double,
    val p95: Double,
    val muestras: Int,
    @ColumnInfo(name = "actualizado_ts") val actualizadoTs: Long
)

/** Dominios ya vistos en sesiones anteriores (solo el nombre y la primera vez). */
@Entity(tableName = "dominios_vistos")
data class DominioVistoEntity(
    @PrimaryKey val dominio: String,
    @ColumnInfo(name = "primera_vez") val primeraVez: Long
)

data class FuenteConteo(val fuente: String, val n: Int)

data class MuestraFila(val metrica: String, val hora: Int, val valor: Int)
