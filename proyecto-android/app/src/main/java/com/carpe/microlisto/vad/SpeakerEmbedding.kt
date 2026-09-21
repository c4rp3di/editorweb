package com.carpe.microlisto.vad

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Wrapper de LiteRT para el modelo de embeddings WeSpeaker.
 *
 * Entrada:  [1, 500, 80] float32  (500 frames de 80 bins mel)
 * Salida:   [1, 256]    float32  (embedding L2-normalizado de 256 dims)
 *
 * El modelo espera exactamente 500 frames. Si el segmento de audio tiene más,
 * se recorta; si tiene menos, se rellena con ceros hasta los 500.
 */
class SpeakerEmbedding(
    context: Context,
    modeloEnAssets: String = "wespeaker_emb_fp16.tflite"
) {
    private val numFrames = 500
    private val numMelBins = 80
    private val dimEmbedding = 256

    private val interpreter: Interpreter

    init {
        val modelo = cargarModeloDesdeAssets(context, modeloEnAssets)
        val opciones = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(modelo, opciones)
    }

    /**
     * @param fbank matriz de [numFramesReales][80]
     * @return embedding de 256 dims, L2-normalizado
     */
    fun calcular(fbank: Array<FloatArray>): FloatArray {
        // Preparar entrada [1, 500, 80]
        val entrada = FloatArray(numFrames * numMelBins)
        val n = minOf(fbank.size, numFrames)
        for (f in 0 until n) {
            val fila = fbank[f]
            for (m in 0 until numMelBins) {
                entrada[f * numMelBins + m] = if (m < fila.size) fila[m] else 0f
            }
        }
        // Si fbank.size < 500, el resto ya está a 0 (relleno)

        val bufEntrada = ByteBuffer
            .allocateDirect(numFrames * numMelBins * 4)
            .order(ByteOrder.nativeOrder())

        val fb = bufEntrada.asFloatBuffer()
        fb.put(entrada)
        fb.rewind()

        val bufSalida = Array(1) { FloatArray(dimEmbedding) }

        interpreter.run(bufEntrada, bufSalida)

        return l2Normalizar(bufSalida[0])
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