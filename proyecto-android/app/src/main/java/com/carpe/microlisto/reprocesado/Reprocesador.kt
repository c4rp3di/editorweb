package com.carpe.microlisto.reprocesado

import android.content.Context
import com.carpe.microlisto.analisis.AnalizadorConversacion
import com.carpe.microlisto.analisis.GeneradorResumen
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.data.Segmento
import com.carpe.microlisto.debug.DebugLog
import com.carpe.microlisto.vad.Diarizer
import com.carpe.microlisto.vad.SegmentoDiarizado
import com.carpe.microlisto.whisper.WhisperManager
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

    /**
     * Reprocesa una conversación. Cambios respecto a la versión anterior:
     * - Es suspend (necesario para llamar a WhisperManager.transcribirWav)
     * - Si Whisper está descargado y carga bien, se usa para re-transcribir
     *   el WAV completo antes de repartir el texto entre segmentos.
     * - Si Whisper no está disponible o falla, se conserva la transcripción
     *   original (Vosk) y se sigue con la diarización normal.
     */
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

        // === PASO 1: Intentar re-transcribir con Whisper ===
        var textoFinal = conversacion.transcripcion
        var usoWhisper = false

        try {
            val wm = WhisperManager(context.applicationContext)
            if (wm.estaDescargado()) {
                DebugLog.info("Reprocesador", "Whisper disponible, transcribiendo WAV completo…")
                val textoWhisper = wm.transcribirWav(archivo)
                if (!textoWhisper.isNullOrBlank()) {
                    textoFinal = textoWhisper
                    usoWhisper = true
                    DebugLog.info("Reprocesador", "Whisper devolvió ${textoWhisper.length} caracteres")
                } else {
                    DebugLog.warn("Reprocesador", "Whisper devolvió texto vacío, se conserva la transcripción de Vosk")
                }
            } else {
                DebugLog.info("Reprocesador", "Whisper no descargado, se conserva la transcripción de Vosk")
            }
        } catch (e: Exception) {
            DebugLog.error("Reprocesador", "Error usando Whisper: ${e.message}")
            // Se conserva textoFinal tal cual
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

        // === PASO 3: Reparto del texto entre segmentos ===
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
        if (usoWhisper) {
            db.actualizarTranscripcion(conversacion.id, textoFinal)
        }

        // === PASO 4: Resumen ===
        try {
            val metricas = AnalizadorConversacion.analizar(segmentosBD, conversacion.duracionMs)
            val resumen = GeneradorResumen.generar(metricas)
            db.actualizarResumen(conversacion.id, resumen)
            DebugLog.info("Reprocesador", "Resumen generado: ${resumen.length} caracteres")
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