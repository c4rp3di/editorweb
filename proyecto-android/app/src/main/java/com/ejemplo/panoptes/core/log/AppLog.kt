package com.carpe.panoptes.core.log

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro interno de la app (visible y copiable en Ajustes → Registro interno).
 * Escribe en filesDir/logs/panoptes.log; al pasar de ~400 KB conserva solo la última mitad.
 * Nunca lanza excepciones: registrar no puede romper nada.
 */
object AppLog {
    private const val MAX_BYTES = 400_000L
    @Volatile private var archivo: File? = null
    private val formato = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(filesDir: File) {
        try {
            val dir = File(filesDir, "logs").apply { mkdirs() }
            archivo = File(dir, "panoptes.log")
            val previo = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                e("CRASH", "Excepción no controlada en hilo ${t.name}", e)
                previo?.uncaughtException(t, e)
            }
            i("APP", "Inicio de la app (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL})")
        } catch (_: Throwable) { }
    }

    fun i(etiqueta: String, msg: String) = escribir("I", etiqueta, msg, null)
    fun w(etiqueta: String, msg: String, t: Throwable? = null) = escribir("W", etiqueta, msg, t)
    fun e(etiqueta: String, msg: String, t: Throwable? = null) = escribir("E", etiqueta, msg, t)

    @Synchronized
    private fun escribir(nivel: String, etiqueta: String, msg: String, t: Throwable?) {
        try {
            when (nivel) { "E" -> Log.e("Panoptes/$etiqueta", msg, t); "W" -> Log.w("Panoptes/$etiqueta", msg, t); else -> Log.i("Panoptes/$etiqueta", msg) }
            val f = archivo ?: return
            val sb = StringBuilder()
            sb.append(formato.format(Date())).append(' ').append(nivel).append(' ').append(etiqueta).append(": ").append(msg).append('\n')
            if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')
            if (f.length() > MAX_BYTES) {
                val texto = f.readText()
                f.writeText(texto.substring(texto.length / 2).substringAfter('\n'))
            }
            f.appendText(sb.toString())
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun leer(): String = try { archivo?.takeIf { it.isFile }?.readText() ?: "" } catch (_: Throwable) { "" }

    @Synchronized
    fun borrar() { try { archivo?.writeText("") } catch (_: Throwable) { } }
}
