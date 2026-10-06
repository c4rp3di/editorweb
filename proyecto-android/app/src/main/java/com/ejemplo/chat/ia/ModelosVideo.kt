package com.ejemplo.chat.ia

import android.content.Context
import java.io.File

class ModelosVideo(private val context: Context) {
    data class ModeloVideo(
        val id: String, val nombre: String, val descripcion: String, val backend: String,
        val estado: Estado, val artefactos: List<ModelWeightsManager.Artifact>
    )
    enum class Estado { NO_DISPONIBLE, LISTO }

    companion object {
        private const val DIFF_URL = "https://huggingface.co/calcuis/wan-1.3b-gguf/resolve/8fbc08db6015e810b6e15d55e220f7f987a2ef56/wan2.1_t2v_1.3b-q4_k_m.gguf"
        private const val T5_URL = "https://huggingface.co/city96/umt5-xxl-encoder-gguf/resolve/986f6ea1e12ecd8fe3d3e6f72bfe90775892cb6c/umt5-xxl-encoder-Q4_K_M.gguf"
        private const val VAE_URL = "https://huggingface.co/Comfy-Org/Wan_2.1_ComfyUI_repackaged/resolve/main/split_files/vae/wan_2.1_vae.safetensors"
        val MODELOS = listOf(ModeloVideo(
            "wan21-t2v-1.3b-q4", "Wan2.1 T2V 1.3B · Q4", "Texto a vídeo local · 1.03 GB + UMT5 Q4 + VAE", "stable-diffusion.cpp + Vulkan", Estado.LISTO,
            listOf(
                ModelWeightsManager.Artifact("wan2.1_t2v_1.3b-q4_k_m.gguf", DIFF_URL, "f3c1a3fb984d49d3963cc4a93d4f5103deef5909eb5e948513fb6c6d582a350e", 0L, "diffusion"),
                ModelWeightsManager.Artifact("umt5-xxl-encoder-Q4_K_M.gguf", T5_URL, "17cf97a5bbbc60a646d6105b832b6f657ce904a8a1ad970e4b59df0c67584a40", 3_655_145_312L, "t5xxl"),
                ModelWeightsManager.Artifact("wan_2.1_vae.safetensors", VAE_URL, "2fc39d31359a4b0a64f55876d8ff7fa8d780956ae2cb13463b0223e15148976b", 253_815_318L, "vae")
            )
        ))
    }
    private val root = File(context.filesDir, "modelos-video").apply { mkdirs() }
    private val weights = ModelWeightsManager(context)
    fun carpeta(m: ModeloVideo) = File(root, m.id).apply { mkdirs() }
    fun verificacion(m: ModeloVideo) = weights.verifyAll(carpeta(m), m.artefactos)
    fun puedeUsarse(m: ModeloVideo) = verificacion(m).ok
    fun descargar(m: ModeloVideo, onProgress: (Int) -> Unit = {}) {
        val total = m.artefactos.sumOf { it.expectedBytes }.coerceAtLeast(1L); var done = 0L
        m.artefactos.forEach { a -> weights.download(carpeta(m), a) { p -> onProgress(((done + a.expectedBytes * p / 100L) * 100L / total).toInt()) }; done += a.expectedBytes }
        verificacion(m)
    }
    fun espacioLibreBytes() = root.usableSpace
}
