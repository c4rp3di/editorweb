package com.carpe.microlisto.transcripcion

import android.content.Context
import com.carpe.microlisto.data.RutasPublicas
import com.carpe.microlisto.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Gestor del modelo Vosk grande (vosk-model-es-0.42, ~1.4 GB comprimido,
 * ~2.5 GB descomprimido).
 *
 * El modelo se descarga en /storage/emulated/0/Microlisto/vosk/ y se
 * descomprime allí. Sobrevive a desinstalaciones de la app.
 */
class VoskManager(private val context: Context) {

    private val urlModelo = "https://alphacephei.com/vosk/models/vosk-model-es-0.42.zip"

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)  // archivos grandes, timeout largo
        .build()

    fun estaDescargado(): Boolean {
        val dir = RutasPublicas.rutaModeloVoskDescomprimido()
        if (!dir.exists() || !dir.isDirectory) return false
        // El modelo Vosk requiere al menos estos archivos para ser válido
        val requeridos = listOf("final.mdl", "HCLr.fst", "Gr.fst", "words.txt")
        return requeridos.all { File(dir, it).exists() }
    }

    fun rutaModelo(): String = RutasPublicas.rutaModeloVoskDescomprimido().absolutePath

    /**
     * Descarga el ZIP y lo descomprime. onProgress recibe valores 0-100.
     * Fases: 0-70% descarga, 70-100% descompresión.
     */
    suspend fun descargar(onProgress: (Int, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val zipTemp = File(RutasPublicas.dirVosk(), "vosk-model-es-0.42.zip")
        return@withContext try {
            RutasPublicas.dirVosk().mkdirs()

            // ==== Fase 1: Descarga (0-70%) ====
            DebugLog.info("Vosk", "Iniciando descarga desde $urlModelo")
            val peticion = Request.Builder().url(urlModelo).build()
            val respuesta = cliente.newCall(peticion).execute()

            if (!respuesta.isSuccessful) {
                DebugLog.error("Vosk", "Error HTTP: ${respuesta.code}")
                return@withContext false
            }

            val cuerpo = respuesta.body ?: return@withContext false
            val tamanoTotal = cuerpo.contentLength()
            var bytesLeidos = 0L

            cuerpo.byteStream().use { entrada ->
                BufferedOutputStream(FileOutputStream(zipTemp)).use { salida ->
                    val buffer = ByteArray(64 * 1024)
                    var leidos: Int
                    while (entrada.read(buffer).also { leidos = it } != -1) {
                        salida.write(buffer, 0, leidos)
                        bytesLeidos += leidos
                        if (tamanoTotal > 0) {
                            val pct = ((bytesLeidos * 70) / tamanoTotal).toInt()
                            onProgress(pct, "Descargando… ${bytesLeidos / (1024 * 1024)} MB / ${tamanoTotal / (1024 * 1024)} MB")
                        }
                    }
                }
            }
            DebugLog.info("Vosk", "Descarga completada: ${zipTemp.length()} bytes")

            // ==== Fase 2: Descompresión (70-100%) ====
            onProgress(70, "Descomprimiendo…")
            val destino = RutasPublicas.dirVosk()
            descomprimirZip(zipTemp, destino) { pctParcial ->
                val pctGlobal = 70 + (pctParcial * 30 / 100)
                onProgress(pctGlobal, "Descomprimiendo… $pctParcial%")
            }

            // Borrar ZIP temporal
            try { zipTemp.delete() } catch (_: Exception) {}

            val ok = estaDescargado()
            if (ok) {
                DebugLog.info("Vosk", "Modelo Vosk listo en ${rutaModelo()}")
                onProgress(100, "Listo")
            } else {
                DebugLog.error("Vosk", "El modelo no se descomprimió correctamente")
            }
            ok
        } catch (e: Exception) {
            DebugLog.error("Vosk", "Error: ${e.message}")
            e.printStackTrace()
            try { zipTemp.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Descomprime un ZIP a un directorio. Usa ZipFile (no carga todo en
     * memoria) para poder manejar archivos grandes como el modelo Vosk.
     */
    private fun descomprimirZip(zip: File, destino: File, onProgreso: (Int) -> Unit) {
        val zipFile = ZipFile(zip)
        try {
            val entradas = zipFile.entries()
            val total = zipFile.size()
            var procesadas = 0

            while (entradas.hasMoreElements()) {
                val entrada = entradas.nextElement()
                val archivoDestino = File(destino, entrada.name)

                // Evitar "zip slip" (rutas maliciosas tipo ../../)
                if (!archivoDestino.canonicalPath.startsWith(destino.canonicalPath)) {
                    continue
                }

                if (entrada.isDirectory) {
                    archivoDestino.mkdirs()
                } else {
                    archivoDestino.parentFile?.mkdirs()
                    zipFile.getInputStream(entrada).use { entradaStream ->
                        BufferedInputStream(entradaStream).use { bufferedIn ->
                            FileOutputStream(archivoDestino).use { salida ->
                                BufferedOutputStream(salida).use { bufferedOut ->
                                    val buffer = ByteArray(64 * 1024)
                                    var leidos: Int
                                    while (bufferedIn.read(buffer).also { leidos = it } != -1) {
                                        bufferedOut.write(buffer, 0, leidos)
                                    }
                                }
                            }
                        }
                    }
                }
                procesadas++
                if (total > 0) {
                    onProgreso((procesadas * 100 / total))
                }
            }
        } finally {
            try { zipFile.close() } catch (_: Exception) {}
        }
    }

    /**
     * Borra el modelo descargado. Libera ~2.5 GB.
     */
    fun borrar(): Boolean {
        return try {
            val dir = RutasPublicas.rutaModeloVoskDescomprimido()
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            DebugLog.info("Vosk", "Modelo borrado")
            true
        } catch (e: Exception) {
            DebugLog.warn("Vosk", "Error borrando: ${e.message}")
            false
        }
    }
}