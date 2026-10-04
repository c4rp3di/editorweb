package com.carpe.espejoslocos

import android.content.Context
import android.content.SharedPreferences
import org.opencv.imgproc.Imgproc

/**
 * Configuración de la cámara y el render. Persiste entre sesiones con
 * SharedPreferences (una línea de código, sin necesidad de marcar la
 * funcionalidad "Preferencias" del generador).
 */
object ConfigCamara {

    data class Resolucion(val ancho: Int, val alto: Int, val etiqueta: String, val descripcion: String)

    val resoluciones: List<Resolucion> = listOf(
        Resolucion(640, 480, "480p", "Fluido"),
        Resolucion(1280, 720, "720p", "Equilibrado"),
        Resolucion(1920, 1080, "1080p", "Calidad")
    )

    private const val PREFS = "espejoslocos_config"
    private var prefs: SharedPreferences? = null

    var resolucion: Resolucion = resoluciones[0]
        set(value) { field = value; guardar() }

    var fps: Int? = null // null = auto
        set(value) { field = value; guardar() }

    var interpolacion: Int = Imgproc.INTER_LINEAR
        set(value) { field = value; guardar() }

    var espejarFrontal: Boolean = true
        set(value) { field = value; guardar() }

    var mostrarFps: Boolean = false
        set(value) { field = value; guardar() }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p

        val ancho = p.getInt("ancho", 640)
        val alto = p.getInt("alto", 480)
        resolucion = resoluciones.find { it.ancho == ancho && it.alto == alto } ?: resoluciones[0]

        val fpsGuardado = p.getInt("fps", 0)
        fps = if (fpsGuardado > 0) fpsGuardado else null

        interpolacion = p.getInt("interpolacion", Imgproc.INTER_LINEAR)
        espejarFrontal = p.getBoolean("espejarFrontal", true)
        mostrarFps = p.getBoolean("mostrarFps", false)
    }

    private fun guardar() {
        val p = prefs ?: return
        p.edit()
            .putInt("ancho", resolucion.ancho)
            .putInt("alto", resolucion.alto)
            .putInt("fps", fps ?: 0)
            .putInt("interpolacion", interpolacion)
            .putBoolean("espejarFrontal", espejarFrontal)
            .putBoolean("mostrarFps", mostrarFps)
            .apply()
    }

    fun etiquetaInterpolacion(): String = when (interpolacion) {
        Imgproc.INTER_CUBIC -> "Cúbica"
        Imgproc.INTER_NEAREST -> "Vecino"
        else -> "Lineal"
    }
}