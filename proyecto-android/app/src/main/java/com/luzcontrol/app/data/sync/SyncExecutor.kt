package com.luzcontrol.app.data.sync

import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import com.luzcontrol.app.data.notification.NotificationHelper
import com.luzcontrol.app.data.notification.PriceAlertNotifier
import com.luzcontrol.app.data.widget.PriceWidgetProvider
import com.luzcontrol.app.domain.model.AppError
import com.luzcontrol.app.domain.model.SyncMode
import com.luzcontrol.app.domain.repository.SyncRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/** Lógica común de los dos workers (diario y bajo demanda). */
@Singleton
class SyncExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: SyncRepository,
    private val notifier: PriceAlertNotifier,
    private val helper: NotificationHelper
) {
    fun foregroundInfo(): ForegroundInfo = helper.foregroundInfo()

    suspend fun run(mode: SyncMode, runAttemptCount: Int): ListenableWorker.Result {
        val result = sync.sync(mode)
        try {
            PriceWidgetProvider.requestUpdate(context)
            if (mode == SyncMode.DAILY) notifier.checkAndNotify()
        } catch (_: Exception) {
            // Widget y avisos son accesorios: un fallo aquí no debe invalidar la sincronización.
        }
        val blocking = result.errors.filter { it !is AppError.NoCredentials }
        if (blocking.isEmpty()) return ListenableWorker.Result.success()
        val retryable = blocking.any { it is AppError.RateLimited || it is AppError.NoNetwork || it is AppError.Server || it is AppError.Unknown }
        return if (retryable && runAttemptCount < MAX_ATTEMPTS) ListenableWorker.Result.retry()
        else if (result.partial) ListenableWorker.Result.success()
        else ListenableWorker.Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}
