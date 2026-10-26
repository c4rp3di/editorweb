package com.vigia.app.integridad

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.vigia.app.MainActivity
import com.vigia.app.R
import com.vigia.app.analyze.AjustesAnalisis
import com.vigia.app.core.Estado
import com.vigia.app.core.SesionUi
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Recordatorios de higiene digital. Aparecen al abrir Vigía (no hay ninguna tarea permanente en segundo plano, para
 * respetar el diseño episódico); la única notificación espontánea es el aviso de una sesión que lleva demasiado abierta.
 */
object Higiene {
    private const val PREFS = "vigia_higiene"
    private const val DIA_MS = 86_400_000L
    private const val CANAL = "vigia_higiene"

    fun marcarRevision(contexto: Context) {
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("ultima_revision", System.currentTimeMillis()).apply()
    }

    /** Hasta tres recordatorios pendientes, los más importantes primero. */
    fun recordatorios(contexto: Context, ui: SesionUi): List<String> {
        val res = ArrayList<String>()
        val ahora = System.currentTimeMillis()

        val inicio = ui.inicio
        if (ui.estado != Estado.INACTIVA && inicio != null && ahora - inicio >= 14L * DIA_MS) {
            val dias = (ahora - inicio) / DIA_MS
            res.add("Tu sesión lleva " + dias + " días abierta. Ciérrala y revisa los hallazgos: las sesiones largas gastan batería y acumulan datos.")
        }

        val diasSinReiniciar = SystemClock.elapsedRealtime() / DIA_MS
        if (diasSinReiniciar >= 7L) {
            res.add("Hace " + diasSinReiniciar + " días que no reinicias el teléfono. Reiniciarlo cada semana elimina algunas amenazas que solo viven en memoria.")
        }

        val ultima = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("ultima_revision", 0L)
        if (ultima == 0L || ahora - ultima > 30L * DIA_MS) {
            res.add("Hace tiempo que no revisas la superficie del teléfono (accesos especiales, certificados, parches). Tarda un minuto en 🧹 Superficie.")
        }

        val indicadores = AjustesAnalisis(contexto).ultimaActualizacionIoc
        if (indicadores == 0L || ahora - indicadores > 14L * DIA_MS) {
            res.add("Los indicadores de compromiso están desactualizados (o sin cargar). Actualízalos en ⚙ Ajustes.")
        }

        try {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH)
            if (f != null && ahora - f.time > 120L * DIA_MS) {
                res.add("Tu parche de seguridad es de " + Build.VERSION.SECURITY_PATCH + ". Busca actualizaciones del sistema.")
            }
        } catch (e: Exception) {
        }
        return res.take(3)
    }

    /** Una sola notificación cuando una sesión supera los 14 días abierta. */
    fun avisarSesionLarga(contexto: Context, sesionId: Long, inicio: Long) {
        val ahora = System.currentTimeMillis()
        if (ahora - inicio < 14L * DIA_MS) return
        val prefs = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val clave = "larga_" + sesionId
        if (prefs.getBoolean(clave, false)) return
        prefs.edit().putBoolean(clave, true).apply()

        val nm = contexto.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CANAL, contexto.getString(R.string.canal_higiene), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val abrir = PendingIntent.getActivity(
            contexto, 0, Intent(contexto, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val texto = contexto.getString(R.string.aviso_sesion_larga)
        val n = NotificationCompat.Builder(contexto, CANAL)
            .setSmallIcon(R.drawable.ic_vigia)
            .setContentTitle(contexto.getString(R.string.aviso_sesion_larga_titulo))
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        nm.notify(3000, n)
    }
}
