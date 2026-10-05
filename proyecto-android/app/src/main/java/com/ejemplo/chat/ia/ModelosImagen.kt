package com.ejemplo.chat.ia

import android.content.Context
import java.io.File

/**
 * Catálogo de modelos de imagen.
 *
 * Esta capa NO ejecuta inferencia y NO descarga modelos automáticamente.
 * El backend de imagen se conectará cuando haya pasado la prueba nativa
 * correspondiente en el dispositivo objetivo.
 */
class ModelosImagen(private val context: Context) {
    data class ModeloImagen(
        val id: String,
        val nombre: String,
        val descripcion: String,
        val backend: String,
        val estado: Estado = Estado.PLANIFICADO,
        val tamanoAprox: String = "Por verificar"
    )

    enum class Estado { PLANIFICADO, NO_DISPONIBLE, LISTO }

    companion object {
        val MODELOS = listOf(
            ModeloImagen(
                id = "sd-mobile",
                nombre = "Motor de imagen móvil",
                descripcion = "Backend local basado en stable-diffusion.cpp. Se incorporará después de verificar una configuración compatible con el Xiaomi 13T Pro.",
                backend = "stable-diffusion.cpp + Vulkan",
                estado = Estado.PLANIFICADO
            )
        )
    }

    private val root = File(context.filesDir, "modelos-imagen").apply { mkdirs() }

    fun carpeta(m: ModeloImagen): File = File(root, m.id).apply { mkdirs() }

    /** Compatibilidad con futuras implementaciones; no descarga nada. */
    fun descargado(m: ModeloImagen): Boolean = false

    fun progreso(m: ModeloImagen): Int = 0

    fun faltantes(m: ModeloImagen): List<String> = emptyList()

    fun espacioLibreBytes(): Long = root.usableSpace

    fun puedeUsarse(m: ModeloImagen): Boolean = m.estado == Estado.LISTO && descargado(m)
}
