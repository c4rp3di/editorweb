package com.luzcontrol.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.luzcontrol.app.domain.model.SyncMode
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Sincronización periódica (cada 24 h, con red y batería no baja). */
@HiltWorker
class DailySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val executor: SyncExecutor
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = executor.run(SyncMode.DAILY, runAttemptCount)
}

/** Sincronización manual desde la UI (expedited cuando el sistema lo permite). */
@HiltWorker
class OnDemandSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val executor: SyncExecutor
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = executor.run(SyncMode.ON_DEMAND, runAttemptCount)
    override suspend fun getForegroundInfo(): ForegroundInfo = executor.foregroundInfo()
}
