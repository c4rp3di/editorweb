package com.carpe.microlisto.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gestor del modelo Whisper para transcripción final de alta calidad.
 *
 * El modelo se descarga bajo confirmación del usuario (500 MB aprox) y se
 * guarda en filesDir/whisper/. A partir de ahí, la transcripción final usa
 * Whisper en lugar de Vosk.
 */
class WhisperManager(private val context: Context) {

    private val dirWhisper = File(context.filesDir, "whisper")
    private val archivoModelo = File(dirWhisper, MODELO_NOMBRE)

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun estaDescargado(): Boolean {
        return archivoModelo.exists() && archivoModelo.length() > 100_000_000L
    }

    /**
     * Descarga el modelo con callback de progreso (0.0 a 1.0).
     * Devuelve true si la descarga fue exitosa.
     */
    suspend fun descargar(onProgreso: (Float) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                dirWhisper.mkdirs()
                val peticion = Request.Builder().url(URL_MODELO).build()
                val respuesta = cliente.newCall(peticion).execute()
                if (!respuesta.isSuccessful) return@withContext false

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
                                onProgreso(bytesLeidos.toFloat() / tamanoTotal)
                            }
                        }
                    }
                }
                return@withContext estaDescargado()
            } catch (e: Exception) {
                try { archivoModelo.delete() } catch (_: Exception) {}
                false
            }
        }
    }

    fun rutaModelo(): String = archivoModelo.absolutePath

    /**
     * Borra el modelo descargado. Útil desde Ajustes.
     */
    fun borrar() {
        try { archivoModelo.delete() } catch (_: Exception) {}
    }

    companion object {
        private const val MODELO_NOMBRE = "whisper-base.es.tflite"
        // Modelo Whisper Base en español (~249 MB). Transcripción offline en
        // formato .tflite, ya verificado para Android.
        private const val URL_MODELO =
            "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-base.es.tflite"
    }
}