package com.carpe.microlisto.whisper

import android.content.Context
import com.carpe.microlisto.debug.DebugLog
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperModel
import com.whispercpp.whisper.TranscribeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gestor de Whisper basado en la librería whisperGF (AAR).
 *
 * El modelo se descarga la primera vez que se usa (o cuando el usuario lo
 * pide desde Ajustes) y se guarda en filesDir/whisper/. A partir de ahí,
 * se carga bajo demanda y se usa para transcribir el WAV completo.
 */
class WhisperManager(private val context: Context) {

    private val dirWhisper = File(context.filesDir, "whisper")

    // Modelo elegido: large-v3-turbo en Q5_K_M (809 MB). Es el mejor
    // equilibrio calidad/tamaño para el Dimensity 9200+.
    // Si en el futuro se quiere más ligero, cambiar a MEDIUM_IQ4_XS (600 MB).
    private val modeloElegido = WhisperModel.LARGE_V3_TURBO_Q5_K_M

    @Volatile
    private var ctx: WhisperContext? = null

    fun estaDescargado(): Boolean {
        return try {
            // whisperGF usa internamente este patrón de nombre para el modelo
            val archivo = buscarArchivoGguf()
            archivo != null && archivo.exists() && archivo.length() > 100_000_000L
        } catch (_: Exception) {
            false
        }
    }

    private fun buscarArchivoGguf(): File? {
        if (!dirWhisper.exists()) return null
        val archivos = dirWhisper.listFiles()
        return archivos?.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }
    }

    /**
     * Descarga el modelo si no está presente. onProgress recibe un valor
     * de 0 a 100.
     */
    suspend fun descargar(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            dirWhisper.mkdirs()
            DebugLog.info("Whisper", "Iniciando descarga de ${modeloElegido.name}")
            val nuevoCtx = WhisperContext.createFromDownload(
                model = modeloElegido,
                cacheDir = dirWhisper,
                onProgress = { progress ->
                    val pct = (progress.percent * 100).toInt()
                    onProgress(pct)
                }
            )
            ctx?.release()
            ctx = nuevoCtx
            DebugLog.info("Whisper", "Modelo descargado y cargado")
            true
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error descargando: ${e.message}")
            false
        }
    }

    /**
     * Carga el modelo si ya está descargado. Devuelve true si está listo.
     */
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
     * Transcribe un archivo WAV (16 kHz mono PCM 16-bit). Devuelve el texto
     * completo, o null si falla.
     */
    suspend fun transcribirWav(wav: File): String? = withContext(Dispatchers.IO) {
        if (ctx == null) {
            if (!cargarSiDescargado()) {
                DebugLog.warn("Whisper", "Modelo no disponible para transcribir")
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
            val config = TranscribeConfig(
                language = "es",
                detectLanguage = false,
                translate = false,
                enableVad = false
            )
            val resultado = ctx?.transcribe(muestras, config)
            val texto = resultado?.fullText?.trim()
            DebugLog.info("Whisper", "Transcripción completada: ${texto?.length ?: 0} caracteres")
            texto
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error transcribiendo: ${e.message}")
            null
        }
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

    fun borrar() {
        try {
            ctx?.release()
            ctx = null
            dirWhisper.listFiles()?.forEach { it.delete() }
            DebugLog.info("Whisper", "Modelo borrado")
        } catch (e: Exception) {
            DebugLog.warn("Whisper", "Error borrando: ${e.message}")
        }
    }

    fun liberar() {
        try {
            ctx?.release()
            ctx = null
        } catch (_: Exception) {}
    }
}