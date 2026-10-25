package com.vigia.app.alert

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vigia.app.HallazgosActivity
import com.vigia.app.R
import com.vigia.app.SessionDetailActivity
import com.vigia.app.analyze.Severidad
import com.vigia.app.store.AlertLogEntity
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.FindingEntity
import com.vigia.app.util.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Alertas. Regla de oro: la alerta no puede depender solo del teléfono, así que además de la
 * notificación local hay canales externos (servidor propio por HTTPS y SMS a un contacto de confianza).
 * Lo que sale del teléfono es mínimo: hora, gravedad, título genérico del hallazgo y un identificador
 * (hash). Nunca nombres de apps, dominios ni datos crudos. Cada hallazgo se avisa una sola vez por canal.
 */
object Alertas {
    private const val CANAL_NOTIF = "vigia_hallazgos"

    class Mensaje(val ts: Long, val severidad: String, val titulo: String, val hash: String)

    fun clave(f: FindingEntity): String {
        val estable = if (f.regla == "CORR") {
            f.ts.toString()
        } else {
            try {
                val a = JSONArray(f.evidenciaIds)
                if (a.length() > 0) a.optString(0) else f.ts.toString()
            } catch (e: Exception) {
                f.ts.toString()
            }
        }
        return f.regla + "|" + f.app + "|" + f.titulo + "|" + estable
    }

    fun huella(texto: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 6) sb.append(String.format("%02x", d[i].toInt() and 0xFF))
        return sb.toString()
    }

    /** Revisa los hallazgos de la sesión y avisa de los que todavía no se habían avisado. */
    suspend fun procesar(contexto: Context, sesionId: Long) {
        withContext(Dispatchers.IO) {
            val app = contexto.applicationContext
            val bd = AppDatabase.obtener(app)
            val registro = bd.informesDao()
            val ajustes = AjustesAlertas(app)
            val hallazgos = bd.analisisDao().listaFindings(sesionId).filter { it.severidad != Severidad.INFO }
            if (hallazgos.isEmpty()) return@withContext
            val ahora = System.currentTimeMillis()

            // ---- notificación local: alta y crítica ----
            if (ajustes.notificacionLocal) {
                val ya = registro.clavesAlertadas("local", sesionId).toHashSet()
                val nuevos = hallazgos.filter { Severidad.peso(it.severidad) >= 8 && !ya.contains(clave(it)) }
                if (nuevos.isNotEmpty()) {
                    notificar(app, sesionId, nuevos)
                    registro.registrarAlertas(nuevos.map { AlertLogEntity(clave(it), "local", sesionId, ahora) })
                }
            }

            // ---- canales externos ----
            val paraExterno = hallazgos.filter { Severidad.peso(it.severidad) >= ajustes.pesoUmbral() }
            if (paraExterno.isEmpty()) return@withContext
            val canales = ArrayList<String>()
            if (ajustes.httpsActivo && ajustes.httpsUrl.isNotEmpty()) canales.add("https")
            if (ajustes.smsActivo && ajustes.smsNumero.isNotEmpty()) canales.add("sms")
            for (canal in canales) {
                val ya = registro.clavesAlertadas(canal, sesionId).toHashSet()
                val nuevos = paraExterno.filter { !ya.contains(clave(it)) }
                if (nuevos.isEmpty()) continue
                val mensajes = mensajesPara(nuevos)
                var todoBien = true
                for (m in mensajes) {
                    val resultado = enviar(app, canal, m)
                    if (resultado != null) todoBien = false
                }
                if (todoBien) {
                    registro.registrarAlertas(nuevos.map { AlertLogEntity(clave(it), canal, sesionId, ahora) })
                }
            }
        }
    }

    /** Sin spam: si hay muchos hallazgos nuevos se manda un solo resumen. */
    private fun mensajesPara(nuevos: List<FindingEntity>): List<Mensaje> {
        val ordenados = nuevos.sortedByDescending { Severidad.peso(it.severidad) }
        if (ordenados.size <= 3) {
            return ordenados.map { Mensaje(it.ts, it.severidad, it.titulo, huella(clave(it))) }
        }
        val criticos = nuevos.count { it.severidad == Severidad.CRITICA }
        val altos = nuevos.count { it.severidad == Severidad.ALTA }
        val medios = nuevos.count { it.severidad == Severidad.MEDIA }
        val titulo = nuevos.size.toString() + " hallazgos nuevos (" + criticos + " críticos, " + altos + " altos, " + medios + " medios)"
        val hash = huella(nuevos.map { clave(it) }.sorted().joinToString("#"))
        return listOf(Mensaje(ordenados[0].ts, ordenados[0].severidad, titulo, hash))
    }

    /** Devuelve null si se envió bien, o el motivo del fallo. */
    suspend fun enviar(contexto: Context, canal: String, m: Mensaje): String? = withContext(Dispatchers.IO) {
        try {
            if (canal == "https") enviarHttps(contexto, m) else enviarSms(contexto, m)
        } catch (e: Exception) {
            e.javaClass.simpleName + " " + (e.message ?: "")
        }
    }

    private fun enviarHttps(contexto: Context, m: Mensaje): String? {
        val ajustes = AjustesAlertas(contexto)
        val url = ajustes.httpsUrl
        if (!url.startsWith("https://")) return "La dirección debe empezar por https://"
        val cuerpo = JSONObject()
            .put("origen", "vigia")
            .put("version", 1)
            .put("timestamp_ms", m.ts)
            .put("severidad", m.severidad)
            .put("descripcion", m.titulo)
            .put("hash", m.hash)
            .toString()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("User-Agent", "Vigia")
            val token = ajustes.token()
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer " + token)
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            return if (codigo in 200..299) null else "El servidor respondió HTTP " + codigo
        } catch (e: IOException) {
            return e.javaClass.simpleName + " " + (e.message ?: "")
        } finally {
            c.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun enviarSms(contexto: Context, m: Mensaje): String? {
        val numero = AjustesAlertas(contexto).smsNumero
        if (numero.isEmpty()) return "Falta el número del contacto"
        if (ContextCompat.checkSelfPermission(contexto, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "Vigía no tiene permiso para enviar SMS"
        }
        val texto = "Vigía [" + Severidad.etiqueta(m.severidad) + "] " + Formato.fecha(m.ts) + " · " + m.titulo + " · id " + m.hash
        val recortado = if (texto.length > 150) texto.substring(0, 150) else texto
        val sms = if (Build.VERSION.SDK_INT >= 31) {
            contexto.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        sms.sendTextMessage(numero, null, recortado, null, null)
        return null
    }

    /** Alerta de prueba: no es un hallazgo real, sirve para comprobar que el canal funciona. */
    suspend fun prueba(contexto: Context, canal: String): String {
        val m = Mensaje(System.currentTimeMillis(), Severidad.INFO, "Alerta de prueba de Vigía", huella("prueba" + System.currentTimeMillis()))
        val fallo = enviar(contexto, canal, m)
        return fallo ?: "Enviada correctamente"
    }

    private fun notificar(app: Context, sesionId: Long, nuevos: List<FindingEntity>) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL_NOTIF, app.getString(R.string.canal_hallazgos), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val criticos = nuevos.count { it.severidad == Severidad.CRITICA }
        val altos = nuevos.count { it.severidad == Severidad.ALTA }
        val texto = "Sesión #" + sesionId + ": " + criticos + " crítico(s) y " + altos + " alto(s) por revisar. " +
            "Es algo anómalo, no una prueba de infección."
        val intent = Intent(app, HallazgosActivity::class.java)
        intent.putExtra(SessionDetailActivity.EXTRA_SESION, sesionId)
        val abrir = PendingIntent.getActivity(
            app, sesionId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(app, CANAL_NOTIF)
            .setSmallIcon(R.drawable.ic_vigia)
            .setContentTitle(app.getString(R.string.notif_hallazgos_titulo))
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        nm.notify(2000 + sesionId.toInt(), n)
    }
}
