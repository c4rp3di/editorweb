package com.ejemplo.chat.ia

import android.content.Context
import java.io.File

/**
 * Catálogo local de modelos de imagen.
 *
 * El motor nativo (stable-diffusion.cpp + Vulkan) se compila en GitHub Actions.
 * Los pesos de los modelos NO forman parte del APK y nunca se descargan de
 * forma automática desde esta clase.
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
                nombre = "Stable Diffusion móvil",
                descripcion = "Motor local basado en stable-diffusion.cpp con backend Vulkan. Los pesos se incorporarán como modelos descargables y verificables en una fase posterior.",
                backend = "stable-diffusion.cpp + Vulkan",
                estado = Estado.LISTO,
                tamanoAprox = "Depende del modelo descargado"
            )
        )
    }

    private val root = File(context.filesDir, "modelos-imagen").apply { mkdirs() }

    fun carpeta(m: ModeloImagen): File = File(root, m.id).apply { mkdirs() }

    /**
     * Esta versión solo comprueba la presencia de un archivo marcador creado
     * por la futura capa de descarga/verificación. No descarga nada.
     */
    fun descargado(m: ModeloImagen): Boolean = carpeta(m).listFiles()?.any {
        it.isFile && (it.extension.equals("safetensors", true) || it.extension.equals("gguf", true) || it.extension.equals("ckpt", true))
    } == true

    fun progreso(m: ModeloImagen): Int = if (descargado(m)) 100 else 0

    fun faltantes(m: ModeloImagen): List<String> = if (descargado(m)) emptyList() else listOf("Pesos del modelo")

    fun espacioLibreBytes(): Long = root.usableSpace

    fun puedeUsarse(m: ModeloImagen): Boolean = m.estado == Estado.LISTO && descargado(m)
}
