package com.ejemplo.tt

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

// Tarea de ejemplo para WorkManager. Edita doWork() con lo que necesites
// ejecutar periódicamente en segundo plano (mínimo cada 15 minutos: es el
// límite que impone Android para trabajo periódico).
class TareaPeriodica(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // Tu código aquí.
        return Result.success()
    }
}
