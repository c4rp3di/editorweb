package com.imagen.chat.ia

import android.content.Context
import java.io.File

/**
 * Catálogo de modelos de imagen. Solo Stable Diffusion 1.5.
 * Los pesos no se incluyen en el APK: se descargan tras confirmación del usuario
 * y se activan solo después de verificar SHA-256.
 */
class ModelosImagen(private val context: Context) {
    data class ModeloImagen(
        val id: String,
        val nombre: String,
        val descripcion: String,
        val backend: String,
        val artefactos: List<ModelWeightsManager.Artifact>
    )

    companion object {
        private const val SD_URL =
            "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/f03de327dd89b501a01da37fc5240cf4fdba85a1/v1-5-pruned-emaonly.safetensors"

        val SD15 = ModeloImagen(
            id = "sd15",
            nombre = "Stable Diffusion 1.5",
            descripcion = "Texto a imagen local · SD 1.5 · Vulkan",
            backend = "stable-diffusion.cpp + Vulkan",
            artefactos = listOf(
                ModelWeightsManager.Artifact(
                    fileName = "v1-5-pruned-emaonly.safetensors",
                    url = SD_URL,
                    sha256 = "6ce0161689b3853acaa03779ec93eafe75a02f4ced659bee03f50797806fa2fa",
                    expectedBytes = 4_265_146_304L,
                    role = "diffusion"
                )
            )
        )

        val MODELOS = listOf(SD15)
    }

    private val root = File(context.filesDir, "modelos-imagen").apply { mkdirs() }
    private val weights = ModelWeightsManager(context)

    fun carpeta(m: ModeloImagen): File = File(root, m.id).apply { mkdirs() }

    fun verificacion(m: ModeloImagen) = weights.verifyAll(carpeta(m), m.artefactos)

    /** Comprobación rápida (sin hash) para la UI. */
    fun descargado(m: ModeloImagen): Boolean = weights.presentAll(carpeta(m), m.artefactos)

    fun descargar(m: ModeloImagen, onProgress: (Int) -> Unit = {}) {
        m.artefactos.forEach { weights.download(carpeta(m), it, onProgress) }
        verificacion(m)
    }

    fun eliminar(m: ModeloImagen): Boolean {
        val dir = carpeta(m)
        var ok = true
        m.artefactos.forEach { a -> val f = File(dir, a.fileName); if (f.exists() && !f.delete()) ok = false }
        return ok
    }

    fun espacioLibreBytes(): Long = root.usableSpace
}
