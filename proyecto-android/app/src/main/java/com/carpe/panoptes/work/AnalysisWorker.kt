package com.carpe.panoptes.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.R
import com.carpe.panoptes.analyzer.orchestrator.AnalysisOrchestrator
import com.carpe.panoptes.core.db.AnalysisStatus

/**
 * Ejecuta el pipeline de F1 en segundo plano, con notificación en primer
 * plano para que el sistema no lo mate a mitad de análisis (§6.1 del plan).
 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ANALYSIS_ID = "analysisId"
        const val KEY_APK_PATH = "apkPath"
        private const val CHANNEL_ID = "panoptes_analysis"
        private const val NOTIF_ID = 1001
    }

    override suspend fun doWork(): Result {
        val analysisId = inputData.getString(KEY_ANALYSIS_ID) ?: return Result.failure()
        val apkPath = inputData.getString(KEY_APK_PATH) ?: return Result.failure()

        setForeground(crearForegroundInfo())

        val app = applicationContext as PanoptesApp
        return try {
            val orquestador = AnalysisOrchestrator(applicationContext, app.database, app.moduleManager)
            orquestador.run(analysisId, apkPath)

            app.database.analysisDao().getById(analysisId)?.let { entidad ->
                app.database.analysisDao().update(
                    entidad.copy(status = AnalysisStatus.COMPLETADO, finishedAt = System.currentTimeMillis())
                )
            }
            Result.success()
        } catch (e: Exception) {
            app.database.analysisDao().getById(analysisId)?.let { entidad ->
                app.database.analysisDao().update(entidad.copy(status = AnalysisStatus.ERROR))
            }
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = crearForegroundInfo()

    private fun crearForegroundInfo(): ForegroundInfo {
        crearCanalSiHaceFalta()
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.analisis_iniciado))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
        // Android 14 (targetSdk 34) prohíbe iniciar un servicio en primer plano "sin tipo"
        // (InvalidForegroundServiceTypeException). El tipo debe coincidir con el declarado
        // en el manifest para SystemForegroundService (dataSync) y con el permiso
        // FOREGROUND_SERVICE_DATA_SYNC.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    private fun crearCanalSiHaceFalta() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Análisis de APKs", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
