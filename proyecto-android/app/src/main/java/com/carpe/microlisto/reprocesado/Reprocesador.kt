package com.carpe.microlisto.reprocesado

import android.content.Context
import com.carpe.microlisto.analisis.AnalizadorConversacion
import com.carpe.microlisto.analisis.GeneradorResumen
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.data.Segmento
import com.carpe.microlisto.debug.DebugLog
import com.carpe.microlisto.transcripcion.Transcriber
import com.carpe.microlisto.transcripcion.VoskManager
import com.carpe.microlisto.vad.Diarizer
import com.carpe.microlisto.vad.SegmentoDiarizado
import java.io.File
import java.io.RandomAccessFile

data class AjustesReproceso(
    val numHablantes: Int = 0,
    val umbral: Float = 0.55f,
    val minFragMs: Long = 2000L,
    val gapMs: Long = 500L,
    val hopMs: Int = 5000
)

data class ResultadoReproceso(
    val ok: Boolean,
    val numHablantes: Int = 0,
    val numSegmentos: Int = 0,
    val mensaje: String = "",
    val transcribioConWhisper: Boolean = false
)

object Reprocesador {

    suspend fun reprocesar(
        context: Context,
        conversacion: Conversacion,
        ajustes: AjustesReproceso,
        db: BaseDatos
    ): ResultadoReproceso {
        val archivo = File(conversacion.rutaAudio)
        if (!archivo.exists()) {
            return ResultadoReproceso(false, mensaje = "El archivo de audio ya no existe")
        }

        // === PASO 1: Re-transcribir si hay modelo Vosk disponible ===
        var textoFinal = conversacion.transcripcion
        var usoWhisper = false

        try {
            val vm = VoskManager(context.applicationContext)
            if (vm.estaDescargado()) {
                DebugLog.info("Reprocesador", "Modelo Vosk disponible, re-transcribiendo WAV completo…")
                val textoNuevo = transcribirConVosk(context, vm.rutaModelo(), archivo)
                if (!textoNuevo.isNullOrBlank()) {
                    textoFinal = textoNuevo
                    DebugLog.info("Reprocesador", "Vosk devolvió ${textoNuevo.length} caracteres")
                }
            } else {
                DebugLog.info("Reprocesador", "Sin modelo Vosk, se conserva transcripción actual")
            }
        } catch (e: Exception) {
            DebugLog.error("Reprocesador", "Error re-transcribiendo: ${e.message}")
        }

        // === PASO 2: Diarización ===
        val muestras = leerWavFloat(archivo)
        if (muestras.isEmpty()) {
            return ResultadoReproceso(false, mensaje = "No se pudo leer el audio")
        }

        val segmentosDiarizados = try {
            val diarizer = Diarizer(
                context = context,
                numHablantesEsperados = ajustes.numHablantes,
                umbralClustering = ajustes.umbral,
                minFragmentoMs = ajustes.minFragMs,
                gapFusionMs = ajustes.gapMs,
                hopVentanaMs = ajustes.hopMs
            )
            val res = diarizer.diarizar(muestras)
            diarizer.cerrar()
            res
        } catch (e: Exception) {
            DebugLog.error("Reprocesador", "Error diarizando: ${e.message}")
            emptyList()
        }

        val numHablantes = segmentosDiarizados.map { it.hablanteId }.distinct().size
        val segmentosConTexto = repartirTextoEnSegmentos(textoFinal, segmentosDiarizados)

        db.eliminarSegmentosDeConversacion(conversacion.id)
        val segmentosBD = segmentosConTexto.map {
            Segmento(
                idConversacion = conversacion.id,
                hablanteId = it.hablanteId,
                inicioMs = it.inicioMs,
                finMs = it.finMs,
                texto = it.texto
            )
        }
        db.insertarSegmentos(conversacion.id, segmentosBD)
        db.actualizarNumHablantes(conversacion.id, numHablantes)
        if (textoFinal != conversacion.transcripcion) {
            db.actualizarTranscripcion(conversacion.id, textoFinal)
        }

        // === PASO 3: Resumen ===
        try {
            val metricas = AnalizadorConversacion.analizar(segmentosBD, conversacion.duracionMs)
            val resumen = GeneradorResumen.generar(metricas)
            db.actualizarResumen(conversacion.id, resumen)
        } catch (e: Exception) {
            DebugLog.warn("Reprocesador", "Error generando resumen: ${e.message}")
        }

        return ResultadoReproceso(
            ok = true,
            numHablantes = numHablantes,
            numSegmentos = segmentosConTexto.size,
            transcribioConWhisper = usoWhisper
        )
    }

    /**
     * Transcribe un WAV con Vosk usando el modelo grande.
     * Alimenta el WAV completo al recognizer en bloques y acumula el texto.
     */
    private suspend fun transcribirConVosk(context: Context, rutaModelo: String, wav: File): String? {
        return try {
            var textoCompleto = StringBuilder()
            var error: String? = null

            val transcriber = Transcriber(
                context = context,
                rutaModelo = rutaModelo,
                onListo = {},
                onParcial = {},
                onFinal = { final ->
                    val regex = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
                    val limpio = regex.find(final)?.groupValues?.getOrNull(1)?.trim() ?: ""
                    if (limpio.isNotBlank()) {
                        if (textoCompleto.isNotEmpty()) textoCompleto.append(" ")
                        textoCompleto.append(limpio)
                    }
                },
                onError = { error = it }
            )
            transcriber.iniciar()

            if (!transcriber.estaListo()) {
                return null
            }

            // Leer el WAV en bloques de 512 muestras y alimentar al recognizer
            val muestras = leerWavFloat(wav)
            var i = 0
            val bloque = FloatArray(512)
            while (i + 512 <= muestras.size) {
                System.arraycopy(muestras, i, bloque, 0, 512)
                transcriber.aceptarFrame(bloque, 512)
                i += 512
            }

            // Forzar el cierre del último segmento
            transcriber.cerrar()

            if (error != null) {
                DebugLog.warn("Reprocesador", "Vosk reportó error: $error")
                return null
            }

            textoCompleto.toString().trim().ifBlank { null }
        } catch (e: Exception) {
            DebugLog.error("Reprocesador", "Error en Vosk: ${e.message}")
            null
        }
    }

    fun repartirTextoEnSegmentos(
        texto: String,
        segmentos: List<SegmentoDiarizado>
    ): List<SegmentoDiarizado> {
        if (segmentos.isEmpty() || texto.isBlank()) return segmentos
        val palabras = texto.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (palabras.isEmpty()) return segmentos

        val sumaDuraciones = segmentos.sumOf { it.finMs - it.inicioMs }.coerceAtLeast(1L)
        val resultado = mutableListOf<SegmentoDiarizado>()
        var indiceActual = 0

        for (i in segmentos.indices) {
            val seg = segmentos[i]
            val proporcion = (seg.finMs - seg.inicioMs).toDouble() / sumaDuraciones
            val palabrasSegmento = if (i == segmentos.size - 1) {
                palabras.size - indiceActual
            } else {
                (palabras.size * proporcion).toInt().coerceAtLeast(1)
            }
            val fin = (indiceActual + palabrasSegmento).coerceAtMost(palabras.size)
            val txt = if (fin > indiceActual) palabras.subList(indiceActual, fin).joinToString(" ") else ""
            indiceActual = fin
            resultado.add(seg.copy(texto = txt))
        }
        return resultado
    }

    fun leerWavFloat(wav: File): FloatArray {
        try {
            RandomAccessFile(wav, "r").use { raf ->
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
                return muestras
            }
        } catch (e: Exception) {
            return FloatArray(0)
        }
    }
}