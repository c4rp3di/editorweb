package com.carpe.microlisto.whisper

import android.content.Context
import com.carpe.microlisto.debug.DebugLog
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

    // Modelo elegido: large-v3-turbo en Q5_K_M (~809 MB). Es el mejor
    // equilibrio calidad/tamaño para el Dimensity 9200+.
    @Volatile
    private var ctx: Any? = null

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
        return dirWhisper.listFiles()?.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }
    }

    suspend fun descargar(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            dirWhisper.mkdirs()
            DebugLog.info("Whisper", "Iniciando descarga de large-v3-turbo Q5_K_M")
            // Llamada directa a la API de whisperGF. Los nombres de método y
            // clase se resuelven por reflection para evitar problemas si el
            // AAR usa un paquete ligeramente distinto al esperado.
            val whisperClass = Class.forName("com.whispercpp.whisper.WhisperContext")
            val modelClass = Class.forName("com.whispercpp.whisper.WhisperModel")

            val modelo = modelClass.enumConstants
                ?.firstOrNull { (it as Enum<*>).name == "LARGE_V3_TURBO_Q5_K_M" }
                ?: modelClass.enumConstants?.lastOrNull()

            if (modelo == null) {
                DebugLog.error("Whisper", "No se encontró el modelo en el enum")
                return@withContext false
            }

            val createMethod = whisperClass.getMethod(
                "createFromDownload",
                modelClass,
                File::class.java,
                Function1::class.java
            )
            val nuevoCtx = createMethod.invoke(null, modelo, dirWhisper, { progress: Any ->
                try {
                    val percent = progress.javaClass.getMethod("getPercent").invoke(progress) as? Float ?: 0f
                    onProgress((percent * 100).toInt())
                } catch (_: Exception) {
                    onProgress(0)
                }
            })
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
            val whisperClass = Class.forName("com.whispercpp.whisper.WhisperContext")
            val createMethod = whisperClass.getMethod("createContextFromFile", String::class.java)
            ctx = createMethod.invoke(null, archivo.absolutePath)
            true
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error cargando modelo: ${e.message}")
            false
        }
    }

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

            val ctxActual = ctx ?: return@withContext null
            // Invocación por reflection del método transcribe
            val transcribeMethod = ctxActual.javaClass.getMethod(
                "transcribe",
                FloatArray::class.java,
                Class.forName("com.whispercpp.whisper.TranscribeConfig")
            )
            val configClass = Class.forName("com.whispercpp.whisper.TranscribeConfig")
            val config = configClass.getConstructor().newInstance()

            val resultado = transcribeMethod.invoke(ctxActual, muestras, config)
            // Extraer el texto completo del resultado
            val texto = try {
                val fullTextMethod = resultado?.javaClass?.getMethod("getFullText")
                fullTextMethod?.invoke(resultado) as? String
            } catch (_: Exception) {
                resultado?.toString()?.trim()
            }
            DebugLog.info("Whisper", "Transcripción completada: ${texto?.length ?: 0} caracteres")
            texto?.trim()
        } catch (e: Exception) {
            DebugLog.error("Whisper", "Error transcribiendo: ${e.message}")
            e.printStackTrace()
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

    suspend fun borrar() = withContext(Dispatchers.IO) {
        try {
            val ctxActual = ctx
            if (ctxActual != null) {
                try {
                    val releaseMethod = ctxActual.javaClass.getMethod("release")
                    releaseMethod.invoke(ctxActual)
                } catch (_: Exception) {}
            }
            ctx = null
            dirWhisper.listFiles()?.forEach { it.delete() }
            DebugLog.info("Whisper", "Modelo borrado")
        } catch (e: Exception) {
            DebugLog.warn("Whisper", "Error borrando: ${e.message}")
        }
    }

    suspend fun liberar() = withContext(Dispatchers.IO) {
        try {
            val ctxActual = ctx
            if (ctxActual != null) {
                val releaseMethod = ctxActual.javaClass.getMethod("release")
                releaseMethod.invoke(ctxActual)
            }
            ctx = null
        } catch (_: Exception) {}
    }
}