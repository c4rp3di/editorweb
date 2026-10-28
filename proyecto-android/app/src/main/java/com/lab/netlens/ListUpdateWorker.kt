package com.lab.netlens

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class ListUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        NetworkTools.init(applicationContext)
        if (!NetworkTools.autoUpdate) return Result.success()
        val url = NetworkTools.savedUrl ?: return Result.success()
        return try { NetworkTools.updateList(url); Result.success() }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun configure(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context.applicationContext)
            val name = "netlens-list-update"
            if (!enabled) { wm.cancelUniqueWork(name); return }
            val work = PeriodicWorkRequestBuilder<ListUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}

