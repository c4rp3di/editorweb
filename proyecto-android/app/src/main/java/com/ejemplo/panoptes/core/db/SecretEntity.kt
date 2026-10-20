package com.carpe.panoptes.core.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Candidatos a secretos embebidos (claves de API, JWT, credenciales...)
 * hallados por coincidencia de patrón + entropía en el DEX. Son
 * candidatos, no confirmaciones — el objetivo es que el dueño del APK
 * detecte si ha dejado algo expuesto por error en su propia app.
 */
@Entity(
    tableName = "secret",
    indices = [Index("analysisId")]
)
data class SecretEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: String,
    val type: String,          // "api_key" | "jwt" | "aws_key" | "generic_high_entropy" ...
    val value: String,
    val sourceFile: String,
    val entropy: Float
)

@Fts4(contentEntity = SecretEntity::class)
@Entity(tableName = "secret_fts")
data class SecretFts(val value: String)
