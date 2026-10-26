package com.vigia.app.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Indicador de compromiso (IoC): dominio, IP, nombre de paquete, proceso o URL. */
@Entity(
    tableName = "iocs",
    indices = [Index(value = ["tipo", "valor"]), Index(value = ["fuente"])]
)
data class IocEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tipo: String,
    val valor: String,
    val fuente: String,
    @ColumnInfo(name = "fecha_alta") val fechaAlta: Long,
    @ColumnInfo(name = "fecha_baja") val fechaBaja: Long? = null
)
