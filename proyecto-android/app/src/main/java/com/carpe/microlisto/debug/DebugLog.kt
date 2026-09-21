package com.carpe.microlisto.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log en memoria, thread-safe, de tamaño limitado (ring buffer).
 * No escribe a disco, no afecta al rendimiento de forma significativa.
 */
object DebugLog {
    private const val MAX_LINEAS = 500
    private val buffer = ArrayDeque<String>(MAX_LINEAS + 1)
    private val formatoHora = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val candado = Any()

    fun log(tag: String, mensaje: String) {
        val hora = formatoHora.format(Date())
        val linea = "[$hora] [$tag] $mensaje"
        synchronized(candado) {
            buffer.addLast(linea)
            while (buffer.size > MAX_LINEAS) buffer.removeFirst()
        }
    }

    fun info(tag: String, mensaje: String) = log(tag, mensaje)
    fun warn(tag: String, mensaje: String) = log(tag, "⚠️ $mensaje")
    fun error(tag: String, mensaje: String) = log(tag, "❌ $mensaje")

    fun obtenerTodo(): String {
        synchronized(candado) {
            if (buffer.isEmpty()) return "(sin entradas)"
            return buffer.joinToString("\n")
        }
    }

    fun limpiar() {
        synchronized(candado) { buffer.clear() }
    }
}