package com.carpe.panoptes.core.repo

import com.carpe.panoptes.core.db.AnalysisEntity
import com.carpe.panoptes.core.db.AnalysisStatus
import com.carpe.panoptes.core.db.PanoptesDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class AnalysisRepository(private val db: PanoptesDatabase) {

    fun observeAll(): Flow<List<AnalysisEntity>> = db.analysisDao().observeAll()

    /** Registra una extracción recién hecha como un nuevo análisis "pendiente". */
    suspend fun registrarExtraccion(
        packageName: String,
        versionName: String?,
        apkPath: String,
        sha256: String
    ): AnalysisEntity {
        val entity = AnalysisEntity(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            versionName = versionName,
            apkPath = apkPath,
            sha256 = sha256,
            startedAt = System.currentTimeMillis(),
            finishedAt = null,
            status = AnalysisStatus.PENDIENTE
        )
        db.analysisDao().insert(entity)
        return entity
    }

    suspend fun marcarCompletado(analysis: AnalysisEntity) {
        db.analysisDao().update(
            analysis.copy(status = AnalysisStatus.COMPLETADO, finishedAt = System.currentTimeMillis())
        )
    }
}
