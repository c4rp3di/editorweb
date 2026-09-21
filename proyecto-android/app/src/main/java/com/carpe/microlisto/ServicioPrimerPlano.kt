package com.carpe.microlisto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

// Servicio en primer plano de ejemplo. La clase MainActivity está en el
// mismo paquete, así que no hace falta importarla si necesitas volver a ella.
class ServicioPrimerPlano : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        crearCanalServicio()
        val notificacion = NotificationCompat.Builder(this, "canal_servicio")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Servicio en ejecución")
            .setContentText("Tu app sigue trabajando en segundo plano")
            .build()
        startForeground(3, notificacion)
        // Tu código aquí.
        return START_STICKY
    }

    private fun crearCanalServicio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel("canal_servicio", "Servicio en primer plano", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }
}
