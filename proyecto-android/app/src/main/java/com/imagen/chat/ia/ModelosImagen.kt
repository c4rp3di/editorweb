package com.imagen.chat.ia

import android.content.Context
import java.io.File

/**
 * Catálogo de modelos de imagen: Stable Diffusion 1.5 (base), DreamShaper 8 y Realistic Vision 5.1.
 * Los tres son arquitectura SD 1.5, así que comparten el mismo motor nativo.
 * Cada modelo vive en su propia carpeta (modelos-imagen/<id>) y ocupa disco por separado.
 * Los pesos no se incluyen en el APK: se descargan tras confirmación del usuario
 * y se activan solo después de verificar SHA-256.
 */
class ModelosImagen(private val context: Context) {
    data class ModeloImagen(
        val id: String,
        val nombre: String,
        val descripcion: String,
        val backend: String,
        val artefactos: List<ModelWeightsManager.Artifact>,
        /** Modelo LCM: se genera con 4-8 pasos, CFG 1-2 y muestreador LCM (≈10× más rápido). */
        val lcm: Boolean = false
    )

    companion object {
        private const val SD_URL =
            "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/f03de327dd89b501a01da37fc5240cf4fdba85a1/v1-5-pruned-emaonly.safetensors"

        // DreamShaper 8 (Lykon) · fp16 podado, VAE incluido · commit fijado.
        private const val DREAMSHAPER_URL =
            "https://huggingface.co/Lykon/DreamShaper/resolve/c6565f0dbcab40e8eb7be2d1e54f49cfc5415127/DreamShaper_8_pruned.safetensors"

        // Realistic Vision 5.1 (SG161222) · fp16 sin EMA · commit fijado.
        private const val REALISTIC_URL =
            "https://huggingface.co/SG161222/Realistic_Vision_V5.1_noVAE/resolve/305469dfd7d7410cff6299d34727e237374b5f36/Realistic_Vision_V5.1_fp16-no-ema.safetensors"

        // DreamShaper 8 LCM (Lykon) · mismo estilo que DreamShaper 8 pero destilado para pocos pasos.
        private const val DREAMSHAPER_LCM_URL =
            "https://huggingface.co/Lykon/dreamshaper-8-lcm/resolve/9b718ce5fffa8f557c76bddfd7e525916615ba45/DreamShaper8_LCM.safetensors"

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

        val DREAMSHAPER = ModeloImagen(
            id = "dreamshaper8",
            nombre = "DreamShaper 8",
            descripcion = "Versátil: ilustración, fantasía y semirrealista · SD 1.5 · Vulkan",
            backend = "stable-diffusion.cpp + Vulkan",
            artefactos = listOf(
                ModelWeightsManager.Artifact(
                    fileName = "DreamShaper_8_pruned.safetensors",
                    url = DREAMSHAPER_URL,
                    sha256 = "879db523c30d3b9017143d56705015e15a2cb5628762c11d086fed9538abd7fd",
                    expectedBytes = 2_132_625_894L,
                    role = "diffusion"
                )
            )
        )

        val REALISTIC_VISION = ModeloImagen(
            id = "realisticvision51",
            nombre = "Realistic Vision 5.1",
            descripcion = "Fotorrealismo (personas, retratos, escenas reales) · SD 1.5 · Vulkan",
            backend = "stable-diffusion.cpp + Vulkan",
            artefactos = listOf(
                ModelWeightsManager.Artifact(
                    fileName = "Realistic_Vision_V5.1_fp16-no-ema.safetensors",
                    url = REALISTIC_URL,
                    sha256 = "99a75a901f4fec732056930a89fa34cf360f6f72d75ce5bf333ddf82adf8dd2a",
                    expectedBytes = 2_132_625_894L,
                    role = "diffusion"
                )
            )
        )

        val DREAMSHAPER_LCM = ModeloImagen(
            id = "dreamshaper8lcm",
            nombre = "DreamShaper 8 LCM · rápido",
            descripcion = "Como DreamShaper 8 pero en 4-8 pasos (≈10× más rápido) · LCM · Vulkan",
            backend = "stable-diffusion.cpp + Vulkan",
            lcm = true,
            artefactos = listOf(
                ModelWeightsManager.Artifact(
                    fileName = "DreamShaper8_LCM.safetensors",
                    url = DREAMSHAPER_LCM_URL,
                    sha256 = "a4f3e1526c5dc4fcbe342f5c410d83ae202c7a415fcefcbb92e0f93fcd0a87c3",
                    expectedBytes = 2_133_804_992L,
                    role = "diffusion"
                )
            )
        )

        val MODELOS = listOf(SD15, DREAMSHAPER, REALISTIC_VISION, DREAMSHAPER_LCM)
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

    /** Ruta del checkpoint principal (rol "diffusion"). */
    fun rutaModelo(m: ModeloImagen): String =
        File(carpeta(m), (m.artefactos.firstOrNull { it.role == "diffusion" } ?: m.artefactos.first()).fileName).absolutePath

    /** Ruta del VAE externo si el modelo lo declara; "" si el checkpoint ya lo lleva integrado. */
    fun rutaVae(m: ModeloImagen): String =
        m.artefactos.firstOrNull { it.role == "vae" }?.let { File(carpeta(m), it.fileName).absolutePath } ?: ""

    /** Disco ocupado ahora mismo por los modelos descargados. */
    fun bytesEnDisco(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
