package com.carpe.microlisto.whisper

import android.content.Context
import com.carpe.microlisto.data.AjustesWhisper
import com.carpe.microlisto.data.RutasPublicas
import com.carpe.microlisto.debug.DebugLog
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.TranscribeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gestor de Whisper. El modelo se guarda en /storage/emulated/0/Microlisto/whisper/
 * para sobrevivir a desinstalaciones.
 *
 * Carga explícita de libwhisper.so y libwhisper_v8fp16_va.so:
 * El AAR de whisperGF v3.0.0 incluye AMBAS librerías nativas, pero su
 * <clinit> solo carga una de ellas automáticamente. Algunos símbolos JNI
 * (como initContext) residen en la otra, así que forzamos la carga de las
 * dos antes de cualquier llamada nativa.
 */
class WhisperManager(private val context: Context) {

    private val nombreModelo = "whisper-large-v3-turbo-Q5_K_M.gguf"
    private val urlModelo = "https://huggingface.co/handy-computer/whisper-large-v3-turbo-gguf/resolve/main/whisper-large-v3-turbo-Q5_K_M.gguf"

    private val archivoModelo: File
        get() = if (RutasPublicas.hayAcceso()) {
            RutasPublicas.archivoModeloWhisper(nombreModelo)
        } else {
            File(File(context.filesDir, "whisper"), nombreModelo)
        }

    init {
        // Cargar explícitamente ambas librerías nativas de whisperGF.
        // El AAR carga una automáticamente en su <clinit>, pero los símbolos
        // JNI pueden estar repartidos entre las dos. Forzando ambas, nos
        // aseguramos de que initContext y el resto están disponibles.
        try {
            System.loadLibrary("whisper")
            DebugLog.info("Whisper", "✅ libwhisper.so cargada")
        } catch (e: UnsatisfiedLinkError) {
            DebugLog.warn("Whisper", "⚠️ libwhisper.so: ${e.message}")
        }
        try {
            System.loadLibrary("whisper_v8fp16_va")
            DebugLog.info("Whisper", "✅ libwhisper_v8fp16_va.so cargada")
        } catch (e: UnsatisfiedLinkError) {
            DebugLog.warn("Whisper", "⚠️ libwhisper_v8fp16_va.so: ${e.message}")
        }
    }

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var ctx: WhisperContext? = null

    fun estaDescargado(): Boolean {
        return archivoModelo.exists() && archivoModelo.length() > 100_000_000L
    }

    fun rutaModelo(): String = archivoModelo.absolutePath

    suspend fun descargar(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            archivoModelo.parentFile?.mkdirs()
            DebugLog.info("Whisper", "Descargando a ${archivoModelo.absolutePath}")

            val peticion = Request.Builder().url(urlModelo).build()
            val respuesta = cliente.newCall(peticion).execute()

            if (!respuesta.isSuccessful) {
                DebugLog.error("Whisper", "Error HTTP: ${respuesta.code}")
                return@withContext false
            }

            val cuerpo = respuesta.body ?: return@withContext false
            val tamanoTotal = cuerpo.contentLength()
            var bytesLeidos = 0L

            cuerpo.byteStream().use { entrada ->
                FileOutputStream(archivoModelo).use { salida ->
                    val buffer = ByteArray(8192)
                    var leidos: Int
                    while (entrada.read(buffer).also { leidos = it } != -1) {
                        salida.write(buffer, 0, leidos)
                        bytesLeidos += leidos
                        if (tamanoTotal > 0) {
                            onProgress(((bytesLeidos * 100) / tamanoTotal).toInt())
                        }
                    }
                }
            }

            DebugLog.info("Whisper", "Descarga completada: ${archivoModelo.length()} bytes")
            true
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error descargando: ${e.message}")
            e.printStackTrace()
            try { archivoModelo.delete() } catch (_: Exception) {}
            false
        }
    }

    suspend fun cargarSiDescargado(): Boolean = withContext(Dispatchers.IO) {
        if (ctx != null) return@withContext true
        if (!archivoModelo.exists()) return@withContext false

        return@withContext try {
            DebugLog.info("Whisper", "Cargando modelo: ${archivoModelo.absolutePath}")
            ctx = WhisperContext.createContextFromFile(archivoModelo.absolutePath)
            true
        } catch (e: Throwable) {
            DebugLog.error("Whisper", "Error cargando modelo: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    suspend fun cargarDesdeArchivo(rutaAbsoluta: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val origen = File(rutaAbsoluta)
            if (!origen.exists()) {
                DebugLog.warn("Whisper", "El archivo no existe: $rutaAbsoluta")
                return@withContext false
            }
            if (origen.absolutePath != archivoModelo.absolutePath) {
                DebugLog.info("Whisper", "Copiando modelo a ${archivoModelo.absolutePath}")
                archivoModelo.parentFile?.mkdirs()
                origen.copyTo(archivoModelo, overwrite = true)
            }
            DebugLog.info("Whisper", "Cargando modelo desde ${archivoModelo.absolutePath}")
            ctx = WhisperContext.createContextFromFile(archivoModelo.absolutePath)
            true
        } catch (e: Throwable) {
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
            val codigoIdioma = AjustesWhisper.getIdioma(context)
            val config = if (codigoIdioma == AjustesWhisper.AUTO) {
                TranscribeConfig(language = null, detectLanguage = true, translate = false)
            } else {
                TranscribeConfig(language = codigoIdioma, detectLanguage = false, translate = false)
            }

            val resultado = ctxActual.transcribe(muestras, config)
            val texto = extraerTexto(resultado)
            DebugLog.info("Whisper", "Transcripción completada: ${texto?.length ?: 0} caracteres")
            texto?.trim()
        } catch (e: Throwable) {
            DebugLog.error("Whisper", "Error transcribiendo: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun extraerTexto(resultado: Any?): String? {
        if (resultado == null) return null
        try {
            val f = resultado.javaClass.getField("fullText")
            return f.get(resultado) as? String
        } catch (_: Exception) {}
        try {
            val m = resultado.javaClass.getMethod("getFullText")
            return m.invoke(resultado) as? String
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
            try { ctx?.release() } catch (_: Throwable) {}
            ctx = null
            archivoModelo.delete()
            DebugLog.info("Whisper", "Modelo borrado")
        } catch (e: Exception) {
            DebugLog.warn("Whisper", "Error borrando: ${e.message}")
        }
    }

    suspend fun liberar() = withContext(Dispatchers.IO) {
        try {
            ctx?.release()
            ctx = null
        } catch (_: Throwable) {}
    }
}