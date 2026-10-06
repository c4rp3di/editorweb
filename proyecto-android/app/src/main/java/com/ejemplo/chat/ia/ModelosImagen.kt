package com.ejemplo.chat.ia

import android.content.Context
import java.io.File

class ModelosImagen(private val context: Context) {
    data class ModeloImagen(
        val id: String,
        val nombre: String,
        val descripcion: String,
        val backend: String,
        val estado: Estado,
        val artefactos: List<ModelWeightsManager.Artifact>
    )
    enum class Estado { NO_DISPONIBLE, LISTO }

    companion object {
        private const val SD_URL = "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/f03de327dd89b501a01da37fc5240cf4fdba85a1/v1-5-pruned-emaonly.safetensors"
        val MODELOS = listOf(ModeloImagen(
            "sd15", "Stable Diffusion 1.5", "Texto a imagen local · SD 1.5 · Vulkan", "stable-diffusion.cpp + Vulkan", Estado.LISTO,
            listOf(ModelWeightsManager.Artifact("v1-5-pruned-emaonly.safetensors", SD_URL, "6ce0161689b3853acaa03779ec93eafe75a02f4ced659bee03f50797806fa2fa", 4_265_146_304L, "diffusion"))
        ))
    }

    private val root = File(context.filesDir, "modelos-imagen").apply { mkdirs() }
    private val weights = ModelWeightsManager(context)
    fun carpeta(m: ModeloImagen) = File(root, m.id).apply { mkdirs() }
    fun verificacion(m: ModeloImagen) = weights.verifyAll(carpeta(m), m.artefactos)
    /** Rápido (sin hash): apto para el hilo principal. La verificación completa va en verificacion(). */
    fun descargado(m: ModeloImagen): Boolean = weights.presentAll(carpeta(m), m.artefactos)
    fun descargar(m: ModeloImagen, onProgress: (Int) -> Unit = {}) { m.artefactos.forEach { weights.download(carpeta(m), it, onProgress) }; verificacion(m) }
    fun espacioLibreBytes() = root.usableSpace
}
