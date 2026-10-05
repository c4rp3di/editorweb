package com.ejemplo.chat.ia

import android.content.Context
import java.io.File

/** Catálogo de vídeo local. Los pesos se gestionarán fuera del APK. */
class ModelosVideo(private val context: Context) {
    data class ModeloVideo(
        val id: String,
        val nombre: String,
        val descripcion: String,
        val backend: String,
        val estado: Estado = Estado.PLANIFICADO
    )

    enum class Estado { PLANIFICADO, NO_DISPONIBLE, LISTO }

    companion object {
        val MODELOS = listOf(
            ModeloVideo(
                id = "wan21-t2v-1.3b",
                nombre = "Wan2.1 T2V 1.3B",
                descripcion = "Generación de vídeo local mediante stable-diffusion.cpp. Configuración inicial conservadora para Android; los pesos y componentes se descargarán y verificarán por separado.",
                backend = "stable-diffusion.cpp + Vulkan",
                estado = Estado.LISTO
            )
        )
    }

    private val root = File(context.filesDir, "modelos-video").apply { mkdirs() }

    fun carpeta(m: ModeloVideo): File = File(root, m.id).apply { mkdirs() }

    fun puedeUsarse(m: ModeloVideo): Boolean = m.estado == Estado.LISTO && carpeta(m).listFiles()?.any { it.isFile } == true

    fun espacioLibreBytes(): Long = root.usableSpace
}
