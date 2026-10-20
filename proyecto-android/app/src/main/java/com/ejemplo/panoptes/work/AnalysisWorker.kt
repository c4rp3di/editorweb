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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.R
import com.carpe.panoptes.analyzer.orchestrator.AnalysisOrchestrator
import com.carpe.panoptes.analyzer.orchestrator.AnalysisProgressBus
import com.carpe.panoptes.core.db.AnalysisStatus

/**
 * Ejecuta el pipeline de F1 en segundo plano, con notificación en primer
 * plano para que el sistema no lo mate a mitad de análisis (§6.1 del plan).
 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ANALYSIS_ID = "analysisId"
        const val KEY_APK_PATH = "apkPath"
        /** Rutas de los splits de una instalación dividida (el base va en KEY_APK_PATH). */
        const val KEY_EXTRA_APKS = "extraApkPaths"
        private const val CHANNEL_ID = "panoptes_analysis"
        private const val NOTIF_ID = 1001
    }

    override suspend fun doWork(): Result {
        val analysisId = inputData.getString(KEY_ANALYSIS_ID) ?: return Result.failure()
        val apkPath = inputData.getString(KEY_APK_PATH) ?: return Result.failure()
        val extraApks = inputData.getStringArray(KEY_EXTRA_APKS)?.toList() ?: emptyList()

        setForeground(crearForegroundInfo())

        val app = applicationContext as PanoptesApp
        return try {
            // Estado visible desde el primer momento: antes se quedaba en PENDIENTE hasta el final.
            app.database.analysisDao().getById(analysisId)?.let { entidad ->
                app.database.analysisDao().update(entidad.copy(status = AnalysisStatus.EN_CURSO))
            }
            AnalysisProgressBus.report(analysisId, 0, "Empezando")
            val orquestador = AnalysisOrchestrator(applicationContext, app.database, app.moduleManager)
            orquestador.run(analysisId, apkPath, extraApks)

            app.database.analysisDao().getById(analysisId)?.let { entidad ->
                app.database.analysisDao().update(
                    entidad.copy(status = AnalysisStatus.COMPLETADO, finishedAt = System.currentTimeMillis())
                )
            }
            AnalysisProgressBus.report(analysisId, 100, "Completado")
            AnalysisProgressBus.clear(analysisId)
            Result.success()
        } catch (e: CancellationException) {
            // Cancelación (el usuario o el sistema cancelan el trabajo): el registro no puede quedar
            // "en curso" para siempre. Se marca ERROR con su finishedAt y se relanza la cancelación.
            marcarTerminal(app, analysisId, AnalysisStatus.ERROR)
            AnalysisProgressBus.clear(analysisId)
            throw e
        } catch (e: Throwable) {
            // Throwable y no solo Exception: un Error (p. ej. NoClassDefFoundError, OutOfMemoryError)
            // escapaba y dejaba el análisis "pendiente" para siempre.
            marcarTerminal(app, analysisId, AnalysisStatus.ERROR)
            AnalysisProgressBus.clear(analysisId)
            Result.failure()
        }
    }

    /** Estado terminal + finishedAt siempre juntos (auditoría H-10); NonCancellable para que
     *  la escritura se complete aunque la corrutina ya esté cancelada. */
    private suspend fun marcarTerminal(app: PanoptesApp, analysisId: String, estado: AnalysisStatus) {
        withContext(NonCancellable) {
            app.database.analysisDao().getById(analysisId)?.let { entidad ->
                app.database.analysisDao().update(
                    entidad.copy(status = estado, finishedAt = System.currentTimeMillis())
                )
            }
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
