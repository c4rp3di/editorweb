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
import com.vigia.app.analyze.AjustesAnalisis
import com.vigia.app.analyze.sig.IocManager
import com.vigia.app.collect.GestorModulos
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
 * sesión abierta, ofrece el botón de parada inmediata y aloja los módulos de recolección
 * (eventos, sensores, inventario). La VPN DNS y la accesibilidad son componentes aparte.
 */
class SesionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observador: Job? = null
    private lateinit var gestor: SessionManager
    private lateinit var modulos: GestorModulos
    private var vistoAbierta = false
    private var periodico: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        gestor = SessionManager.obtener(applicationContext)
        modulos = GestorModulos(applicationContext, scope)
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
                            modulos.detener()
                            periodico?.cancel()
                            periodico = null
                            if (vistoAbierta) {
                                vistoAbierta = false
                                val cerrada = ui.ultimaId
                                if (cerrada != null) {
                                    publicar(Estado.ACTIVA, null, getString(R.string.notif_analizando))
                                    gestor.finalizar(cerrada)
                                }
                            }
                            // Si mientras se analizaba empezó otra sesión, el servicio sigue.
                            if (gestor.estado.value.estado == Estado.INACTIVA) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        } else {
                            if (!vistoAbierta) {
                                vistoAbierta = true
                                actualizarIndicadoresSiToca()
                                iniciarAnalisisPeriodico()
                            }
                            modulos.sincronizar(ui)
                            publicar(ui.estado, ui.inicio)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        modulos.liberar()
        scope.cancel()
        super.onDestroy()
    }

    /** Durante sesiones largas se analiza cada 30 minutos para avisar de hallazgos graves sin esperar al final. */
    private fun iniciarAnalisisPeriodico() {
        periodico?.cancel()
        periodico = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30L * 60_000L)
                val ui = gestor.estado.value
                val id = ui.sesionId
                if (ui.estado == Estado.ACTIVA && id != null) {
                    gestor.analizar(id, true)
                }
            }
        }
    }

    /** Descarga los indicadores al empezar la sesión, solo si el usuario lo activó y han pasado 12 horas. */
    private fun actualizarIndicadoresSiToca() {
        val ajustes = AjustesAnalisis(applicationContext)
        if (!ajustes.actualizarAlIniciar) return
        val hace = System.currentTimeMillis() - ajustes.ultimaActualizacionIoc
        if (hace < 12L * 3_600_000L) return
        scope.launch(Dispatchers.IO) {
            try {
                IocManager.actualizar(applicationContext)
            } catch (e: Exception) {
            }
        }
    }

    private fun publicar(estado: Estado, inicio: Long?, textoFijo: String? = null) {
        val notificacion = construirNotificacion(estado, inicio, textoFijo)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_NOTIF, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_NOTIF, notificacion)
        }
    }

    private fun construirNotificacion(estado: Estado, inicio: Long?, textoFijo: String?): Notification {
        val texto = if (textoFijo != null) {
            textoFijo
        } else if (estado == Estado.PAUSADA) {
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
