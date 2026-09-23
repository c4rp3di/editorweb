package com.carpe.microlisto.reprocesado

import android.content.Context
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
    val mensaje: String = ""
)

object Reprocesador {

    /**
     * Reprocesa una conversación existente: vuelve a diarizar y, si hay un
     * transcriber disponible (Whisper), vuelve a transcribir el WAV.
     */
    fun reprocesar(
        context: Context,
        conversacion: Conversacion,
        ajustes: AjustesReproceso,
        db: BaseDatos,
        transcripcionNueva: String? = null
    ): ResultadoReproceso {
        val archivo = File(conversacion.rutaAudio)
        if (!archivo.exists()) {
            return ResultadoReproceso(false, mensaje = "El archivo de audio ya no existe")
        }

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

        // Texto a usar: si se ha pasado una transcripción nueva (Whisper), esa;
        // si no, la que ya tenía la conversación (Vosk).
        val textoFinal = transcripcionNueva ?: conversacion.transcripcion

        val segmentosConTexto = repartirTextoEnSegmentos(textoFinal, segmentosDiarizados)

        db.eliminarSegmentosDeConversacion(conversacion.id)
        db.insertarSegmentos(conversacion.id, segmentosConTexto.map {
            Segmento(
                idConversacion = conversacion.id,
                hablanteId = it.hablanteId,
                inicioMs = it.inicioMs,
                finMs = it.finMs,
                texto = it.texto
            )
        })
        db.actualizarNumHablantes(conversacion.id, numHablantes)
        if (transcripcionNueva != null) {
            db.actualizarTranscripcion(conversacion.id, transcripcionNueva)
        }

        return ResultadoReproceso(
            ok = true,
            numHablantes = numHablantes,
            numSegmentos = segmentosConTexto.size
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