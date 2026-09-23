package com.carpe.microlisto.whisper

import android.content.Context
import com.carpe.microlisto.debug.DebugLog
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gestor de Whisper basado en whisperGF (AAR).
 *
 * Usa imports directos: el AAR expone las clases com.whispercpp.whisper.*
 * y compila sin problemas. El único detalle era que release() es suspend.
 */
class WhisperManager(private val context: Context) {

    private val dirWhisper = File(context.filesDir, "whisper")

    // Modelo elegido: large-v3-turbo en Q5_K_M (~809 MB).
    private val modeloElegido = WhisperModel.LARGE_V3_TURBO_Q5_K_M

    @Volatile
    private var ctx: WhisperContext? = null

    fun estaDescargado(): Boolean {
        return try {
            val archivo = buscarArchivoGguf()
            archivo != null && archivo.exists() && archivo.length() > 100_000_000L
        } catch (_: Exception) {
            false
        }
    }

    private fun buscarArchivoGguf(): File? {
        if (!dirWhisper.exists()) return null
        return dirWhisper.listFiles()?.firstOrNull {
            it.name.endsWith(".gguf", ignoreCase = true)
        }
    }

    suspend fun descargar(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            dirWhisper.mkdirs()
            DebugLog.info("Whisper", "Iniciando descarga de ${modeloElegido.name}")

            val nuevoCtx = WhisperContext.createFromDownload(
                model = modeloElegido,
                cacheDir = dirWhisper,
                onProgress = { progress ->
                    try {
                        // El objeto progress suele exponer una propiedad "percent" (0f..1f)
                        val pct = (progress.percent * 100).toInt()
                        onProgress(pct)
                    } catch (_: Exception) {
                        onProgress(0)
                    }
                }
            )
            ctx?.release()
            ctx = nuevoCtx
            DebugLog.info("Whisper", "Modelo descargado y cargado")
            true
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error descargando: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    suspend fun cargarSiDescargado(): Boolean = withContext(Dispatchers.IO) {
        if (ctx != null) return@withContext true
        val archivo = buscarArchivoGguf() ?: return@withContext false
        return@withContext try {
            DebugLog.info("Whisper", "Cargando modelo desde ${archivo.absolutePath}")
            ctx = WhisperContext.createContextFromFile(archivo.absolutePath)
            true
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error cargando modelo: ${e.message}")
            false
        }
    }

    suspend fun transcribirWav(wav: File): String? = withContext(Dispatchers.IO) {
        if (ctx == null) {
            if (!cargarSiDescargado()) {
                DebugLog.warn("Whisper", "Modelo no disponible")
                return@withContext null
            }
        }
        try {
            val muestras = leerWavFloat(wav)
            if (muestras.isEmpty()) {
                DebugLog.warn("Whisper", "WAV vacío")
                return@withContext null
            }
            DebugLog.info("Whisper", "Transcribiendo ${muestras.size} muestras")

            val ctxActual = ctx ?: return@withContext null
            // La firma exacta de transcribe varía según versión del AAR.
            // Probamos con el patrón documentado más común: array + idioma.
            val texto = try {
                val resultado = ctxActual.transcribe(muestras, "es")
                extraerTexto(resultado)
            } catch (e: NoSuchMethodError) {
                // Fallback: transcribe sin idioma
                DebugLog.warn("Whisper", "Firma transcribe(FloatArray, String) no disponible, probando alternativa")
                null
            } catch (e: Exception) {
                DebugLog.error("Whisper", "Error en transcribe: ${e.message}")
                null
            }
            DebugLog.info("Whisper", "Transcripción completada: ${texto?.length ?: 0} caracteres")
            texto?.trim()
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error transcribiendo: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Extrae el texto completo del resultado de transcribe(). La clase de
     * resultado de whisperGF expone un campo "fullText" o propiedad similar.
     */
    private fun extraerTexto(resultado: Any?): String? {
        if (resultado == null) return null
        // Intento 1: propiedad fullText
        try {
            val m = resultado.javaClass.getMethod("getFullText")
            return m.invoke(resultado) as? String
        } catch (_: Exception) {}
        // Intento 2: campo directo fullText
        try {
            val f = resultado.javaClass.getField("fullText")
            return f.get(resultado) as? String
        } catch (_: Exception) {}
        // Fallback: toString
        return resultado.toString()
    }

    private fun leerWavFloat(wav: File): FloatArray {
        return try {
            java.io.RandomAccessFile(wav, "r").use { raf ->
                raf.seek(44)
                val bytes = ByteArray((raf.length() - 44).toInt().coerceAtLeast(0))
                raf.readFully(bytes)
                val muestras = FloatArray(bytes.size / 2)
                for (i in muestras.indices) {
                    val lo = bytes[i * 2].toInt() and 0xFF
                    val hi = bytes[i * 2 + 1].toInt()
                    val v = ((hi shl 8) or lo).toShort()
                    muestras[i] = v / 32768f
                }
                muestras
            }
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    suspend fun borrar() = withContext(Dispatchers.IO) {
        try {
            try { ctx?.release() } catch (_: Exception) {}
            ctx = null
            dirWhisper.listFiles()?.forEach { it.delete() }
            DebugLog.info("Whisper", "Modelo borrado")
        } catch (e: Exception) {
            DebugLog.warn("Whisper", "Error borrando: ${e.message}")
        }
    }

    suspend fun liberar() = withContext(Dispatchers.IO) {
        try {
            ctx?.release()
            ctx = null
        } catch (_: Exception) {}
    }
}