package com.carpe.espejoslocos

import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log de diagnóstico visible desde la propia app. Acumula mensajes con
 * timestamp, captura crashes no controlados y expone todo como texto
 * plano para copiar al portapapeles.
 */
object LogEspejos {

    private const val TAG = "EspejosLocos"
    private const val MAX_ENTRADAS = 400
    private val entradas = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var listener: (() -> Unit)? = null

    fun setListener(l: (() -> Unit)?) { listener = l }

    private fun anadir(nivel: String, mensaje: String) {
        val linea = "${fmt.format(Date())}  $nivel  $mensaje"
        synchronized(entradas) {
            entradas.add(linea)
            while (entradas.size > MAX_ENTRADAS) entradas.removeAt(0)
        }
        val prioridad = when (nivel) {
            "ERROR" -> Log.ERROR
            "WARN"  -> Log.WARN
            else    -> Log.INFO
        }
        Log.println(prioridad, TAG, mensaje)
        listener?.invoke()
    }

    fun i(mensaje: String) = anadir("INFO ", mensaje)
    fun w(mensaje: String) = anadir("WARN ", mensaje)
    fun e(mensaje: String, t: Throwable? = null) {
        anadir("ERROR", mensaje)
        t?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString().split("\n").forEach { linea ->
                if (linea.isNotBlank()) anadir("     ", linea.trim())
            }
        }
    }

    fun textoCompleto(): String {
        val sb = StringBuilder()
        sb.append("=== EspejosLocos · diagnóstico ===\n")
        sb.append("Marca:       ${Build.MANUFACTURER}\n")
        sb.append("Modelo:      ${Build.MODEL}\n")
        sb.append("Android:     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Dispositivo: ${Build.DEVICE}\n")
        sb.append("Fecha:       ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        sb.append("===================================\n\n")
        synchronized(entradas) {
            if (entradas.isEmpty()) sb.append("(sin mensajes)\n")
            else entradas.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }

    fun limpiar() {
        synchronized(entradas) { entradas.clear() }
        listener?.invoke()
    }

    fun instalarCapturaDeCrashes() {
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, throwable ->
            try { e("CRASH NO CONTROLADO en ${hilo.name}", throwable) } catch (_: Throwable) {}
            previo?.uncaughtException(hilo, throwable)
        }
    }
}