package com.ejemplo.chat.ia

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Gestión común de pesos: descarga explícita, tamaño y SHA-256 antes de activar. */
class ModelWeightsManager(private val context: Context) {
    data class Artifact(
        val fileName: String,
        val url: String,
        val sha256: String,
        val expectedBytes: Long,
        val role: String
    )
    data class Result(val ok: Boolean, val message: String, val file: File? = null)

    fun verify(file: File, artifact: Artifact): Result {
        if (!file.isFile) return Result(false, "falta ${artifact.fileName}")
        if (artifact.expectedBytes > 0 && file.length() != artifact.expectedBytes) return Result(false, "tamaño incorrecto ${file.name}: ${file.length()} != ${artifact.expectedBytes}")
        val actual = sha256(file)
        if (!actual.equals(artifact.sha256, ignoreCase = true)) return Result(false, "SHA-256 incorrecto ${file.name}: $actual")
        return Result(true, "OK ${artifact.fileName} · ${artifact.role}", file)
    }

    fun verifyAll(dir: File, artifacts: List<Artifact>): Result {
        if (artifacts.isEmpty()) return Result(false, "catálogo vacío")
        DebugLog.log("WEIGHTS", "verificando ${artifacts.size} artefactos en ${dir.absolutePath}")
        for (a in artifacts) {
            val r = verify(File(dir, a.fileName), a)
            DebugLog.log("WEIGHTS", "${a.role} · ${r.message}")
            if (!r.ok) return r
        }
        return Result(true, "Todos los pesos verificados")
    }

    fun download(dir: File, artifact: Artifact, onProgress: (Int) -> Unit = {}): File {
        dir.mkdirs()
        val target = File(dir, artifact.fileName)
        val part = File(dir, artifact.fileName + ".part")
        DebugLog.log("WEIGHTS", "descarga INICIO · ${artifact.role} · ${artifact.fileName} · ${artifact.expectedBytes} bytes")
        val c = (URL(artifact.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            c.connect()
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} al descargar ${artifact.fileName}")
            val declared = c.contentLengthLong
            if (artifact.expectedBytes > 0 && declared > 0 && declared != artifact.expectedBytes) throw IllegalStateException("El servidor anuncia $declared bytes; se esperaban ${artifact.expectedBytes}")
            FileOutputStreamCompat(part).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(1024 * 1024)
                    var total = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        val p = if (artifact.expectedBytes > 0) ((total * 100) / artifact.expectedBytes).toInt().coerceIn(0, 100) else -1
                        if (p != last) { last = p; onProgress(p) }
                    }
                    out.flushAndSync()
                    if (artifact.expectedBytes > 0 && total != artifact.expectedBytes) throw IllegalStateException("descarga incompleta: $total != ${artifact.expectedBytes}")
                }
            }
            val verified = verify(part, artifact)
            if (!verified.ok) throw IllegalStateException(verified.message)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("No se pudo activar ${artifact.fileName}")
            DebugLog.log("WEIGHTS", "descarga OK · ${artifact.role} · SHA-256 ${artifact.sha256}")
            return target
        } catch (e: Exception) {
            DebugLog.log("WEIGHTS", "⚠ descarga ERROR · ${artifact.fileName} · ${e.javaClass.simpleName}: ${e.message}")
            part.delete()
            throw e
        } finally { c.disconnect() }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class FileOutputStreamCompat(file: File) : java.io.FileOutputStream(file) {
        fun flushAndSync() { flush(); fd.sync() }
    }
}
