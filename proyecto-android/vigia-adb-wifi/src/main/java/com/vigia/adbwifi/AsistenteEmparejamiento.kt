package com.vigia.adbwifi

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.vigia.core.ui.NotificacionSesion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * El código de emparejamiento hay que escribirlo MIENTRAS el diálogo «Emparejar con código» de Android sigue
 * abierto, y ese diálogo se cierra si abres otra app. Por eso el asistente lo pide en una NOTIFICACIÓN con campo
 * de respuesta: se escribe sin salir de los Ajustes.
 */
object AsistenteEmparejamiento {
    private const val CANAL = "adbwifi_emparejar"
    private const val ID = 4401
    const val CLAVE_CODIGO = "codigo_emparejamiento"
    const val EXTRA_PUERTO = "puerto_emparejamiento"

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var busqueda: Job? = null

    private fun canal(c: Context): NotificacionSesion.Datos {
        val d = NotificacionSesion.Datos(CANAL, "Emparejamiento ADB", ID, "ADB inalámbrico", "", textoDetener = null)
        NotificacionSesion.crearCanal(c, d)
        return d
    }

    private fun publicar(c: Context, texto: String, puerto: Int?) {
        val d = canal(c)
        val b = NotificationCompat.Builder(c, d.canalId)
            .setSmallIcon(d.iconoRes)
            .setContentTitle("ADB inalámbrico")
            .setContentText(texto)
            .setAutoCancel(puerto == null)
            .setOnlyAlertOnce(true)
        if (puerto != null) {
            val intent = Intent(c, CodigoReceiver::class.java).putExtra(EXTRA_PUERTO, puerto)
            val pendiente = PendingIntent.getBroadcast(
                c, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val entrada = RemoteInput.Builder(CLAVE_CODIGO).setLabel("Código de 6 dígitos").build()
            val accion = NotificationCompat.Action.Builder(0, "Escribir código", pendiente)
                .addRemoteInput(entrada)
                .build()
            b.addAction(accion)
            b.setOngoing(true)
        }
        c.getSystemService(NotificationManager::class.java).notify(ID, b.build())
    }

    /** Muestra el resultado final (llamado desde el receptor). */
    internal fun resultado(c: Context, texto: String) {
        publicar(c, texto, null)
    }

    /** Busca el servicio de emparejamiento (hasta 2 minutos) y, al encontrarlo, pide el código en una notificación. */
    fun iniciar(contexto: Context) {
        val app = contexto.applicationContext
        busqueda?.cancel()
        publicar(app, "Buscando el emparejamiento… abre «Emparejar dispositivo con código» en Depuración inalámbrica.", null)
        busqueda = ambito.launch {
            val s = DescubridorMdns.buscar(app, DescubridorMdns.TIPO_EMPAREJAMIENTO, 120_000L)
            if (s == null) {
                publicar(app, "No se encontró el emparejamiento. Comprueba que el diálogo sigue abierto y que hay Wi-Fi.", null)
            } else {
                publicar(app, "Listo. Toca «Escribir código» y escribe los 6 dígitos que muestra Android.", s.puerto)
            }
        }
    }

    fun cancelar(contexto: Context) {
        busqueda?.cancel()
        contexto.applicationContext.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}

/** Recibe el código escrito en la notificación y completa el emparejamiento. */
class CodigoReceiver : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        val codigo = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AsistenteEmparejamiento.CLAVE_CODIGO)?.toString() ?: ""
        val puerto = intent.getIntExtra(AsistenteEmparejamiento.EXTRA_PUERTO, 0)
        val app = contexto.applicationContext
        val pendiente = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val r = AdbWifi.proveedor(app).emparejar(puerto, codigo)
                AsistenteEmparejamiento.resultado(app, r.mensaje)
            } catch (e: Exception) {
                AsistenteEmparejamiento.resultado(app, "Error: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                pendiente.finish()
            }
        }
    }
}
