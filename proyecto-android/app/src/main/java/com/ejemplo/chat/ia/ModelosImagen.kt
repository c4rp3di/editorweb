package com.ejemplo.chat.ia

import android.content.Context
import com.ejemplo.chat.ia.flux.Flux2Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Image model catalog and explicit, resumable downloads.
 * Nothing is downloaded during startup, selection, or generation.
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
        val manifiestoRemoto: Boolean = false
    )

    companion object {
        private val fluxCore = listOf(
            "ke_enc0.tflite", "ke_enc1.tflite", "ke_enc2.tflite",
            "kc_prep.tflite", "kc_double0.tflite", "kc_double1.tflite",
            "kc_single0.tflite", "kc_single1.tflite", "kc_single2.tflite", "kc_single3.tflite",
            "kc_final.tflite", "kv_vae.tflite",
            "tokenizer/qwen_embed_fp16.bin", "tokenizer/qwen_merges.txt",
            "tokenizer/qwen_special.txt", "tokenizer/qwen_vocab.txt", "tokenizer/tokenizer_fixture.txt"
        )

        val MODELOS = listOf(
            ModeloImagen(
                id = "flux2-klein-4b",
                nombre = "FLUX.2 [klein] 4B",
                descripcion = "Texto→imagen local con LiteRT GPU. 4 pasos a 256×256. El paquete se descarga de una sola fuente coherente e incluye los datos host necesarios para el runtime.",
                repo = "ZawShiShawn/gestura-flux2-klein-4b-litert-tflite",
                tamanoGb = 7.0f,
                archivos = fluxCore,
                manifiestoRemoto = true
            )
        )
    }

    private val root = File(context.filesDir, "modelos-imagen").apply { mkdirs() }

    fun carpeta(m: ModeloImagen): File = File(root, m.id).apply { mkdirs() }
    fun archivo(m: ModeloImagen, rel: String): File = File(carpeta(m), rel)
    private fun manifestFile(m: ModeloImagen) = File(carpeta(m), ".download-manifest.json")

    fun descargado(m: ModeloImagen): Boolean {
        return if (m.manifiestoRemoto) {
            val files = readManifest(m)
            files.isNotEmpty() && files.all { rel ->
                val f = archivo(m, rel)
                f.isFile && f.length() >= minimumBytes(rel)
            } && Flux2Files.isComplete(carpeta(m))
        } else progreso(m) == 100
    }

    fun progreso(m: ModeloImagen): Int {
        val files = if (m.manifiestoRemoto) readManifest(m).ifEmpty { m.archivos } else m.archivos
        if (files.isEmpty()) return 0
        val valid = files.count { rel ->
            val file = archivo(m, rel)
            file.isFile && file.length() >= minimumBytes(rel)
        }
        return valid * 100 / files.size
    }

    fun faltantes(m: ModeloImagen): List<String> =
        (if (m.manifiestoRemoto) readManifest(m).ifEmpty { m.archivos } else m.archivos).filter { rel ->
            val f = archivo(m, rel)
            !f.isFile || f.length() < minimumBytes(rel)
        }

    fun espacioLibreBytes(): Long = root.usableSpace

    suspend fun descargar(m: ModeloImagen, onProgreso: (Int, String) -> Unit) {
        withContext(Dispatchers.IO) {
            val files = if (m.manifiestoRemoto) resolveRemoteManifest(m) else m.archivos
            val pending = files.filter { rel ->
                val f = archivo(m, rel)
                !f.isFile || f.length() < minimumBytes(rel)
            }
            if (pending.isEmpty()) {
                withContext(Dispatchers.Main) { onProgreso(100, "Completado") }
                return@withContext
            }

            val approximateBytes = (m.tamanoGb * 1_000_000_000L).toLong()
            if (root.usableSpace < approximateBytes + 256L * 1024 * 1024) {
                error("Espacio insuficiente. Deja al menos ${(m.tamanoGb + 0.3f)} GB libres.")
            }

            pending.forEachIndexed { index, rel ->
                ensureActive()
                downloadOne(m, rel) { local ->
                    val global = ((index * 100L + local) / pending.size).toInt().coerceIn(0, 100)
                    withContext(Dispatchers.Main) { onProgreso(global, rel) }
                }
            }
            if (m.manifiestoRemoto) writeManifest(m, files)
            withContext(Dispatchers.Main) { onProgreso(100, "Completado") }
        }
    }

    /** Explicit-download-only manifest lookup. It never executes at startup. */
    private fun resolveRemoteManifest(m: ModeloImagen): List<String> {
        val base = m.archivos.toMutableList()
        val url = URL("https://huggingface.co/api/models/${m.repo}/tree/main?recursive=true&expand=false")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        return try {
            if (connection.responseCode != 200) error("No se pudo consultar el manifiesto de ${m.repo} (HTTP ${connection.responseCode})")
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                if (item.optString("type") != "file") continue
                val path = item.optString("path")
                val keep = path.startsWith("host/") ||
                    path.startsWith("tokenizer/") ||
                    path in m.archivos ||
                    m.archivos.any { core ->
                        val stem = core.removeSuffix(".tflite")
                        path.startsWith("$stem.") && path != core
                    }
                if (keep) base += path
            }
            base.distinct().sorted().also { writeManifest(m, it) }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadOne(m: ModeloImagen, rel: String, onLocal: suspend (Int) -> Unit) {
        val destino = archivo(m, rel)
        destino.parentFile?.mkdirs()
        val parcial = File(destino.parentFile, destino.name + ".part")
        var offset = if (parcial.isFile) parcial.length() else 0L
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://huggingface.co/${m.repo}/resolve/main/$rel?download=true")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")

            var code = connection.responseCode
            if (code == 416) {
                connection.disconnect()
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                offset = 0L
                code = connection.responseCode
                if (parcial.exists()) parcial.delete()
            }
            if (code != 200 && code != 206) error("HTTP $code al descargar $rel")

            val append = code == 206 && offset > 0L
            if (!append) offset = 0L
            val contentLength = connection.contentLengthLong
            val total = if (contentLength > 0) offset + contentLength else -1L
            var done = offset
            connection.inputStream.use { input ->
                FileOutputStream(parcial, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var last = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (p != last) { last = p; onLocal(p) }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (parcial.length() < minimumBytes(rel)) error("Archivo incompleto: $rel")
            if (destino.exists()) destino.delete()
            if (!parcial.renameTo(destino)) error("No se pudo guardar $rel")
        } finally {
            connection?.disconnect()
        }
    }

    private fun writeManifest(m: ModeloImagen, files: List<String>) {
        val array = JSONArray()
        files.forEach { array.put(it) }
        manifestFile(m).writeText(array.toString())
    }

    private fun readManifest(m: ModeloImagen): List<String> {
        val file = manifestFile(m)
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            List(array.length()) { array.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun minimumBytes(rel: String): Long = when {
        rel == "ke_enc0.tflite" || rel == "ke_enc1.tflite" || rel == "ke_enc2.tflite" -> 850L * 1024 * 1024
        rel == "kc_prep.tflite" -> 150L * 1024 * 1024
        rel == "kc_double0.tflite" -> 650L * 1024 * 1024
        rel == "kc_double1.tflite" -> 430L * 1024 * 1024
        rel == "kc_single0.tflite" || rel == "kc_single1.tflite" || rel == "kc_single2.tflite" || rel == "kc_single3.tflite" -> 540L * 1024 * 1024
        rel == "kc_final.tflite" -> 15L * 1024 * 1024
        rel == "kv_vae.tflite" -> 40L * 1024 * 1024
        rel == "tokenizer/qwen_embed_fp16.bin" -> 700L * 1024 * 1024
        rel == "tokenizer/qwen_merges.txt" -> 1L * 1024 * 1024
        rel == "tokenizer/qwen_vocab.txt" -> 1L * 1024 * 1024
        rel == "tokenizer/qwen_special.txt" -> 100L
        rel == "tokenizer/tokenizer_fixture.txt" -> 500L
        rel.startsWith("host/") -> 2L
        else -> 2L
    }
}
