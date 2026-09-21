package com.carpe.microlisto.vad

import android.content.Context
import com.carpe.microlisto.debug.DebugLog
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeakerEmbedding(
    context: Context,
    modeloEnAssets: String = "wespeaker_emb_fp16.tflite"
) {
    private val numFrames = 500
    private val numMelBins = 80
    private val dimEmbedding = 256

    private val interpreter: Interpreter

    init {
        DebugLog.info("WeSpeaker", "Cargando modelo $modeloEnAssets")
        val modelo = cargarModeloDesdeAssets(context, modeloEnAssets)
        DebugLog.info("WeSpeaker", "Modelo cargado, ${modelo.capacity()} bytes")
        val opciones = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(modelo, opciones)
        try {
            val formaEntrada = interpreter.getInputTensor(0).shape()
            val formaSalida = interpreter.getOutputTensor(0).shape()
            DebugLog.info("WeSpeaker", "Forma entrada: ${formaEntrada.contentToString()}")
            DebugLog.info("WeSpeaker", "Forma salida: ${formaSalida.contentToString()}")
        } catch (e: Exception) {
            DebugLog.warn("WeSpeaker", "No se pudieron leer las formas del modelo: ${e.message}")
        }
    }

    fun calcular(fbank: Array<FloatArray>): FloatArray {
        val entrada = FloatArray(numFrames * numMelBins)
        val n = minOf(fbank.size, numFrames)
        for (f in 0 until n) {
            val fila = fbank[f]
            for (m in 0 until numMelBins) {
                entrada[f * numMelBins + m] = if (m < fila.size) fila[m] else 0f
            }
        }

        // Estadísticas de la entrada para detectar NaNs o silencio
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var suma = 0.0
        for (v in entrada) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            suma += v.toDouble()
        }
        val media = suma / entrada.size
        DebugLog.info("WeSpeaker", "Entrada: min=$minV max=$maxV media=$media")

        val bufEntrada = ByteBuffer
            .allocateDirect(numFrames * numMelBins * 4)
            .order(ByteOrder.nativeOrder())

        val fb = bufEntrada.asFloatBuffer()
        fb.put(entrada)
        fb.rewind()

        val bufSalida = Array(1) { FloatArray(dimEmbedding) }

        interpreter.run(bufEntrada, bufSalida)

        val normalizado = l2Normalizar(bufSalida[0])
        var sumaNorm = 0.0
        for (v in normalizado) sumaNorm += v * v
        DebugLog.info("WeSpeaker", "Embedding: norma²=${sumaNorm}, primeros=[${normalizado[0]}, ${normalizado[1]}, ${normalizado[2]}]")

        return normalizado
    }

    private fun l2Normalizar(v: FloatArray): FloatArray {
        var suma = 0f
        for (x in v) suma += x * x
        val norma = kotlin.math.sqrt(suma)
        if (norma < 1e-10f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norma
        return out
    }

    private fun cargarModeloDesdeAssets(context: Context, nombre: String): ByteBuffer {
        context.assets.open(nombre).use { isStream ->
            val bytes = isStream.readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            return buf
        }
    }

    fun cerrar() {
        try {
            interpreter.close()
        } catch (_: Exception) {}
    }
}