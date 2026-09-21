package com.carpe.microlisto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MicrolistoService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INICIAR -> iniciarGrabacion()
            ACTION_PARAR -> {
                detenerGrabacion()
                stopSelf()
            }
            else -> iniciarGrabacion()
        }
        return START_STICKY
    }

    private fun iniciarGrabacion() {
        val notificacion = construirNotificacion()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ID_NOTIFICACION,
                notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(ID_NOTIFICACION, notificacion)
        }
        // El Bloque 2 añadirá aquí el arranque de AudioRecorder
    }

    private fun detenerGrabacion() {
        // El Bloque 2 añadirá aquí la parada de AudioRecorder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID,
                getString(R.string.notif_canal_grabacion),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_canal_grabacion_desc)
                setShowBadge(false)
            }
            val gestor = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            gestor.createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(): Notification {
        val intentAbrir = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingAbrir = PendingIntent.getActivity(
            this, 0, intentAbrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentParar = Intent(this, MicrolistoService::class.java).apply {
            action = ACTION_PARAR
        }
        val pendingParar = PendingIntent.getService(
            this, 1, intentParar,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CANAL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_grabando_titulo))
            .setContentText(getString(R.string.notif_grabando_texto))
            .setContentIntent(pendingAbrir)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notif_accion_parar),
                pendingParar
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CANAL_ID = "microlisto_grabacion"
        const val ID_NOTIFICACION = 1001
        const val ACTION_INICIAR = "com.carpe.microlisto.INICIAR"
        const val ACTION_PARAR = "com.carpe.microlisto.PARAR"

        fun iniciar(context: Context) {
            val intent = Intent(context, MicrolistoService::class.java).apply {
                action = ACTION_INICIAR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun parar(context: Context) {
            val intent = Intent(context, MicrolistoService::class.java).apply {
                action = ACTION_PARAR
            }
            context.startService(intent)
        }
    }
}