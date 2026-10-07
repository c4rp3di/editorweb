package com.ejemplo.chat.ia

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GenDetenida : Exception("Generación detenida")
class GenFallo(mensaje: String) : Exception(mensaje)

/**
 * Lanza el trabajo en GenService (proceso ":gen") y espera el resultado.
 * Solo se admite un trabajo a la vez.
 */
object GenClient {
    @Volatile private var ultimo = DiffusionNative.Progreso(0, 0, 0, 0)
    @Volatile private var pid = 0
    @Volatile private var sesion: Sesion? = null

    /** Último progreso recibido del proceso de generación. */
    fun progreso(): DiffusionNative.Progreso = ultimo

    fun enCurso(): Boolean = sesion != null

    /** RSS (MB) del proceso de generación, para el log; -1 si no se puede leer. */
    fun rssGenMb(): Long = try {
        val p = pid
        if (p <= 0) -1L else File("/proc/$p/status").useLines { l ->
            l.firstOrNull { it.startsWith("VmRSS") }?.filter { it.isDigit() }?.toLongOrNull()?.div(1024) ?: -1L
        }
    } catch (_: Exception) { -1L }

    /** Detiene la generación: mata el proceso ":gen". La corrutina que espera recibe GenDetenida. */
    fun detener() {
        val s = sesion ?: return
        DebugLog.log("GEN", "detener solicitado por el usuario · pid gen=$pid")
        s.terminar(GenDetenida())
    }

    suspend fun ejecutar(context: Context, datos: Bundle) {
        suspendCancellableCoroutine<Unit> { cont ->
            check(sesion == null) { "Ya hay una generación en curso" }
            ultimo = DiffusionNative.Progreso(1, 0, 0, 0)
            pid = 0
            val s = Sesion(context.applicationContext, datos, cont)
            sesion = s
            cont.invokeOnCancellation { s.terminar(null, resumir = false) }
            val ok = try {
                s.app.bindService(Intent(s.app, GenService::class.java), s.conexion, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) { false }
            if (!ok) s.terminar(GenFallo("No se pudo iniciar el proceso de generación"))
        }
    }

    private class Sesion(val app: Context, val datos: Bundle, val cont: CancellableContinuation<Unit>) {
        @Volatile var terminado = false
        val cliente = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { m -> recibir(m); true }))

        val conexion = object : ServiceConnection {
            override fun onServiceConnected(nombre: ComponentName, binder: IBinder) {
                val m = Message.obtain(null, GenProtocolo.MSG_INICIAR)
                m.data = datos
                m.replyTo = cliente
                try { Messenger(binder).send(m) } catch (e: Exception) { terminar(GenFallo("No se pudo enviar el trabajo: ${e.message}")) }
            }
            override fun onServiceDisconnected(nombre: ComponentName) { murio() }
            override fun onBindingDied(nombre: ComponentName) { murio() }
        }

        private fun murio() {
            if (terminado) return
            DebugLog.log("GEN", "⚠ el proceso de generación terminó sin avisar (abort nativo, GPU perdida o memoria)")
            terminar(GenFallo("El motor de generación se cerró inesperadamente (abort nativo, GPU o memoria). Detalle en el log, líneas [NATIVE]."))
        }

        private fun recibir(m: Message) {
            val d = m.data
            when (m.what) {
                GenProtocolo.MSG_PID -> pid = d.getInt("pid")
                GenProtocolo.MSG_PROGRESO ->
                    ultimo = DiffusionNative.Progreso(d.getInt("fase"), d.getInt("paso"), d.getInt("pasos"), d.getLong("ms"))
                GenProtocolo.MSG_FIN -> {
                    val ok = d.getBoolean("ok")
                    val error = d.getString("error") ?: ""
                    terminar(if (ok) null else GenFallo(error.ifBlank { "fallo sin detalle" }))
                }
            }
        }

        /** Cierra la sesión una sola vez: desvincula, mata el proceso si hace falta y reanuda la corrutina. */
        fun terminar(error: Throwable?, resumir: Boolean = true) {
            if (terminado) return
            terminado = true
            val p = pid
            try { app.unbindService(conexion) } catch (_: Exception) {}
            if (sesion === this) sesion = null
            // Matar el proceso si el trabajo no acabó bien (detención, cancelación o fallo) y sigue vivo.
            if (error != null || !resumir) {
                if (p > 0) try { Process.killProcess(p) } catch (_: Exception) {}
            }
            if (error != null && error !is GenDetenida) DebugLog.volcarNativoReciente()
            if (resumir && cont.isActive) {
                if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
            }
        }
    }
}

/**
 * Traduce el contador del motor a una etapa y un porcentaje global.
 *  fase 1: cargando modelo y codificando el prompt · fase 2: muestreo · fase 3: decodificación VAE / guardado.
 *  Pesos del porcentaje: preparación 0–5 % · muestreo 5–90 % · decodificación 90–99 %.
 */
class SeguimientoProgreso(private val pasosSolicitados: Int) {
    class Estado(val porcentaje: Int, val texto: String)

    private var pasoVisto = -1
    private var inicioPasoMs = System.currentTimeMillis()

    fun actualizar(p: DiffusionNative.Progreso, segundos: Int): Estado {
        val ahora = System.currentTimeMillis()
        val muestreo = p.fase == 2 || (p.fase == 1 && p.pasos == pasosSolicitados)
        val tiempo = "%d:%02d".format(segundos / 60, segundos % 60)
        return when {
            muestreo && p.pasos > 0 -> {
                if (p.paso != pasoVisto) { pasoVisto = p.paso; inicioPasoMs = ahora }
                val hechos = p.paso.coerceIn(0, p.pasos)
                // Interpola dentro del paso en curso con la duración del anterior (si se conoce).
                val frac = if (hechos < p.pasos && p.ultimoPasoMs > 0 && hechos > 0)
                    ((ahora - inicioPasoMs).toFloat() / p.ultimoPasoMs).coerceIn(0f, 0.95f) else 0f
                val pct = (5 + 85f * (hechos + frac) / p.pasos).toInt().coerceIn(5, 90)
                val restantes = p.pasos - hechos
                val eta = if (p.ultimoPasoMs > 0 && hechos > 0 && restantes > 0) {
                    val s = (restantes * p.ultimoPasoMs / 1000).toInt()
                    " · quedan ~${"%d:%02d".format(s / 60, s % 60)}"
                } else ""
                Estado(pct, "$pct % · muestreo paso ${hechos + if (hechos < p.pasos) 1 else 0}/${p.pasos} · $tiempo$eta")
            }
            p.fase >= 3 -> {
                // Con 1..64 el contador son los tiles del VAE; con valores mayores es la carga de tensores del VAE.
                if (p.pasos in 1..64 && p.pasos != pasosSolicitados) {
                    val pct = (90 + 9f * p.paso.coerceIn(0, p.pasos) / p.pasos).toInt()
                    Estado(pct, "$pct % · decodificando imagen ${p.paso}/${p.pasos} · $tiempo")
                } else Estado(90, "90 % · preparando decodificación · $tiempo")
            }
            else -> Estado(if (p.fase == 0) 0 else 2, "preparando (cargando modelo / codificando prompt) · $tiempo")
        }
    }
}
