package com.carpe.descompresor.extraer

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.carpe.descompresor.MainActivity
import com.carpe.descompresor.destino.CatalogoDestinos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ServicioExtraccion : Service() {

    companion object {
        const val CANAL = "extraccion"
        const val ID_PROGRESO = 1001
        const val ID_FINAL = 1002
        const val ACCION_CANCELAR = "com.carpe.descompresor.CANCELAR"

        fun iniciar(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServicioExtraccion::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trabajando = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_CANCELAR) {
            EstadoExtraccion.cancelar = true
            return START_NOT_STICKY
        }
        crearCanal()
        ServiceCompat.startForeground(
            this, ID_PROGRESO, construir("Preparando extracción…", 0, 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val sol = EstadoExtraccion.solicitud
        if (sol == null || trabajando) {
            if (!trabajando) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        trabajando = true
        EstadoExtraccion.cancelar = false
        EstadoExtraccion.estado.value = Progreso(activo = true, mensaje = "Preparando…")

        scope.launch {
            var informe: InformeExtraccion? = null
            var error: String? = null
            try {
                val destino = CatalogoDestinos.crear(sol.destinoId)
                val extractor = Extractor(this@ServicioExtraccion, sol.contenedor, destino, sol.opciones) { p ->
                    EstadoExtraccion.estado.value = p
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(
                        ID_PROGRESO,
                        construir("${p.hechos}/${p.total} · ${p.archivoActual}", p.total, p.hechos, p.total == 0)
                    )
                }
                informe = extractor.ejecutar(sol.rutas)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            val inf = informe
            EstadoExtraccion.estado.value = if (inf != null) {
                Progreso(false, "", inf.extraidos.size, inf.extraidos.size, inf.bytes, inf.bytes, inf, "")
            } else {
                Progreso(false, "", 0, 0, 0L, 0L, null, "Error: " + (error ?: "desconocido"))
            }
            val texto = if (inf != null) inf.resumen() else "Error: " + (error ?: "desconocido")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val fin = NotificationCompat.Builder(this@ServicioExtraccion, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Extracción terminada")
                .setContentText(texto)
                .setContentIntent(abrirApp())
                .setAutoCancel(true)
                .build()
            nm.notify(ID_FINAL, fin)
            trabajando = false
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL, "Extracciones", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun abrirApp(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun construir(texto: String, max: Int, actual: Int, indeterminado: Boolean): Notification {
        val cancelar = PendingIntent.getService(
            this, 1,
            Intent(this, ServicioExtraccion::class.java).setAction(ACCION_CANCELAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Extrayendo")
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, actual, indeterminado)
            .setContentIntent(abrirApp())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelar)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
