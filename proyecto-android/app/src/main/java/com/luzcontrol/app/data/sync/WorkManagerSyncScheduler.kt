package com.luzcontrol.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.repository.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val energy: EnergyRepository
) : SyncScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun setPeriodicEnabled(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<OnDemandSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(ON_DEMAND_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun syncOnOpenIfStale(maxAgeHours: Long) {
        scope.launch {
            val last = energy.observeLastSync().first()
            val stale = last == null || !last.success ||
                Duration.between(last.timestamp, Instant.now()).toHours() >= maxAgeHours
            if (stale) syncNow()
        }
    }

    override fun observeSyncing(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(ON_DEMAND_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
            .distinctUntilChanged()

    private companion object {
        const val PERIODIC_NAME = "daily_sync"
        const val ON_DEMAND_NAME = "on_demand_sync"
    }
}
