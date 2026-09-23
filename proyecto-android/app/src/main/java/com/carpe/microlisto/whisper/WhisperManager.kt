package com.carpe.microlisto.whisper

import android.content.Context
import com.carpe.microlisto.debug.DebugLog
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperModel
import com.whispercpp.whisper.TranscribeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WhisperManager(private val context: Context) {

    private val dirWhisper = File(context.filesDir, "whisper")
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
                        val pct = (progress.percent * 100).toInt()
                        onProgress(pct)
                    } catch (_: Exception) {
                        onProgress(0)
                    }
                }
            )
            try { ctx?.release() } catch (_: Exception) {}
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

    /**
     * Carga un modelo desde una ruta arbitraria en el dispositivo.
     * Útil cuando la descarga automática falla y el usuario copia el archivo .gguf manualmente.
     */
    suspend fun cargarDesdeArchivo(rutaAbsoluta: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val archivo = File(rutaAbsoluta)
            if (!archivo.exists()) {
                DebugLog.warn("Whisper", "El archivo no existe: $rutaAbsoluta")
                return@withContext false
            }
            DebugLog.info("Whisper", "Cargando modelo desde ruta manual: $rutaAbsoluta")
            ctx = WhisperContext.createContextFromFile(archivo.absolutePath)
            true
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error cargando desde ruta manual: ${e.message}")
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
            val config = TranscribeConfig()
            val texto = try {
                val resultado = ctxActual.transcribe(muestras, config)
                extraerTexto(resultado)
            } catch (e: NoSuchMethodError) {
                DebugLog.warn("Whisper", "transcribe(FloatArray, TranscribeConfig) no disponible: ${e.message}")
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

    private fun extraerTexto(resultado: Any?): String? {
        if (resultado == null) return null
        try {
            val m = resultado.javaClass.getMethod("getFullText")
            return m.invoke(resultado) as? String
        } catch (_: Exception) {}
        try {
            val f = resultado.javaClass.getField("fullText")
            return f.get(resultado) as? String
        } catch (_: Exception) {}
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