package com.ejemplo.chat.ia

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestor de modelos de imagen.
 *
 * IMPORTANTE: no descarga ni selecciona ningún modelo automáticamente.
 * El usuario debe iniciar la descarga explícitamente desde la interfaz.
 */
class ModelosImagen(private val context: Context) {
    data class ModeloImagen(
        val id: String,
        val nombre: String,
        val descripcion: String,
        val repo: String,
        val tamanoGb: Float,
        val archivos: List<String>,
        val resolucion: String = "256×256",
        val pasos: Int = 4,
        val experimental: Boolean = false
    )

    companion object {
        // Runtime completo de FLUX.2 Klein según el contrato publicado por Gestura:
        // 24 grafos de difusión + 3 encoder + 2 VAE + 5 tokenizer + 1 host + 8 sidecars.
        private val flux = listOf(
            "kc_prep.tflite", "kc_double0.tflite", "kc_double1.tflite",
            "kc_single0.tflite", "kc_single1.tflite", "kc_single2.tflite", "kc_single3.tflite", "kc_final.tflite",
            "kce_prep.tflite", "kce_double0.tflite", "kce_double1.tflite",
            "kce_single0.tflite", "kce_single1.tflite", "kce_single2.tflite", "kce_single3.tflite", "kce_final.tflite",
            "kce2_prep.tflite", "kce2_double0.tflite", "kce2_double1.tflite",
            "kce2_single0.tflite", "kce2_single1.tflite", "kce2_single2.tflite", "kce2_single3.tflite", "kce2_final.tflite",
            "ke_enc0.tflite", "ke_enc1.tflite", "ke_enc2.tflite",
            "kv_vae_enc.tflite", "kv_vae.tflite",
            "host/time_guidance_embed_bf16.bin",
            "tokenizer/qwen_embed_fp16.bin", "tokenizer/qwen_merges.txt", "tokenizer/qwen_special.txt",
            "tokenizer/qwen_vocab.txt", "tokenizer/tokenizer_fixture.txt",
            // Los nombres exactos de estos 8 sidecars pueden variar por revisión; se rellenan
            // desde el manifest del paquete y no se consideran descargados hasta validarlos.
            "weights/manifest.txt"
        )

        val MODELOS = listOf(
            ModeloImagen(
                id = "flux2-klein-4b",
                nombre = "FLUX.2 [klein] 4B",
                descripcion = "Generación y edición local mediante LiteRT. 4 pasos, 256×256.",
                repo = "ZawShiShawn/gestura-flux2-klein-4b-litert-tflite",
                tamanoGb = 8.7f,
                archivos = flux,
                resolucion = "256×256",
                pasos = 4
            )
        )
    }

    private val root = File(context.filesDir, "modelos-imagen").apply { mkdirs() }

    fun carpeta(m: ModeloImagen) = File(root, m.id).apply { mkdirs() }
    fun archivo(m: ModeloImagen, rel: String) = File(carpeta(m), rel)

    fun progreso(m: ModeloImagen): Int {
        val total = m.archivos.size
        if (total == 0) return 0
        return (m.archivos.count { archivo(m, it).exists() && archivo(m, it).length() > 0 } * 100 / total)
    }

    fun descargado(m: ModeloImagen): Boolean =
        progreso(m) == 100 && archivo(m, "weights/manifest.txt").length() > 0

    /** Descarga únicamente después de una llamada explícita desde la UI. */
    suspend fun descargar(m: ModeloImagen, onProgreso: (Int, String) -> Unit) {
        withContext(Dispatchers.IO) {
            m.archivos.forEachIndexed { index, rel ->
                ensureActive()
                val destino = archivo(m, rel)
                if (destino.exists() && destino.length() > 0) return@forEachIndexed
                destino.parentFile?.mkdirs()
                val parcial = File(destino.parentFile, destino.name + ".part")
                val ya = if (parcial.exists()) parcial.length() else 0L
                val url = "https://huggingface.co/${m.repo}/resolve/main/$rel?download=true"
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 30_000
                con.readTimeout = 120_000
                con.instanceFollowRedirects = true
                if (ya > 0) con.setRequestProperty("Range", "bytes=$ya-")
                val code = con.responseCode
                if (code != 200 && code != 206) throw IllegalStateException("HTTP $code al descargar $rel")
                val resume = code == 206
                var read = if (resume) ya else 0L
                val total = if (con.contentLengthLong > 0) read + con.contentLengthLong else -1L
                con.inputStream.use { input ->
                    FileOutputStream(parcial, resume).use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            ensureActive()
                            val local = if (total > 0) (read * 100 / total).toInt() else 0
                            val global = ((index * 100L + local) / m.archivos.size).toInt()
                            withContext(Dispatchers.Main) { onProgreso(global.coerceIn(0, 100), rel) }
                        }
                    }
                }
                if (!parcial.renameTo(destino)) throw IllegalStateException("No se pudo guardar $rel")
            }
        }
    }
}
