package com.carpe.panoptes.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AnalysisStatus { PENDIENTE, EN_CURSO, COMPLETADO, ERROR }

/**
 * F0: solo registramos que se extrajo un APK. Las tablas de permisos,
 * componentes, endpoints, secretos, etc. (§7 del plan) llegan en F1 junto
 * con el parsing DEX real — crearlas vacías ahora sería prematuro.
 */
@Entity(tableName = "analysis")
data class AnalysisEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val versionName: String?,
    val apkPath: String,
    val sha256: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: AnalysisStatus,
    val modulesUsed: String = ""   // CSV, p.ej. "jadx,apktool" — se llena desde F2
)
