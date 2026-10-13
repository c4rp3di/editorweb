package com.vigia.app.core

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
import androidx.core.content.ContextCompat
import com.vigia.app.MainActivity
import com.vigia.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Servicio en primer plano: mantiene la notificación persistente «Vigía activo» mientras hay
 * sesión abierta y ofrece el botón de parada inmediata. En la Fase 2 alojará la VPN local.
 */
class SesionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observador: Job? = null
    private lateinit var gestor: SessionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        gestor = SessionManager.obtener(applicationContext)
        crearCanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android exige llamar a startForeground enseguida tras startForegroundService.
        publicar(Estado.ACTIVA, null)

        if (intent?.action == ACCION_DETENER) {
            scope.launch {
                gestor.cargarSiHaceFalta()
                gestor.detener()
            }
        }

        if (observador == null) {
            observador = scope.launch {
                gestor.cargarSiHaceFalta()
                gestor.estado.collect { ui ->
                    if (ui.cargado) {
                        if (ui.estado == Estado.INACTIVA) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            publicar(ui.estado, ui.inicio)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun publicar(estado: Estado, inicio: Long?) {
        val notificacion = construirNotificacion(estado, inicio)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_NOTIF, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_NOTIF, notificacion)
        }
    }

    private fun construirNotificacion(estado: Estado, inicio: Long?): Notification {
        val texto = if (estado == Estado.PAUSADA) {
            getString(R.string.notif_texto_pausada)
        } else if (inicio != null) {
            val hora = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(inicio))
            getString(R.string.notif_texto_activa, hora)
        } else {
            getString(R.string.estado_activa)
        }

        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val intentDetener = Intent(this, SesionService::class.java).setAction(ACCION_DETENER)
        val detener = PendingIntent.getService(
            this, 1, intentDetener,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_vigia)
            .setContentTitle(getString(R.string.notif_titulo))
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .addAction(0, getString(R.string.detener), detener)
            .build()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel(
                CANAL, getString(R.string.canal_nombre), NotificationManager.IMPORTANCE_LOW
            )
            val gestorNotif = getSystemService(NotificationManager::class.java)
            gestorNotif.createNotificationChannel(canal)
        }
    }

    companion object {
        const val ACCION_DETENER = "com.vigia.app.DETENER"
        private const val CANAL = "vigia_sesion"
        private const val ID_NOTIF = 1

        fun iniciar(contexto: Context) {
            ContextCompat.startForegroundService(
                contexto, Intent(contexto, SesionService::class.java)
            )
        }
    }
}
