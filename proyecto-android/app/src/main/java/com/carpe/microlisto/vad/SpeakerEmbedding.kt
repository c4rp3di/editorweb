package com.carpe.microlisto.vad

import android.content.Context
import com.carpe.microlisto.debug.DebugLog
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrapper de LiteRT para WeSpeaker.
 *
 * El modelo espera [1, 500, 80] = 5 segundos de fbank. Para fragmentos más
 * cortos, se replica el patrón de frames cíclicamente (equivalente al
 * np.resize() que usa extract_emb.py de WeSpeaker). Para fragmentos más
 * largos, se desliza una ventana de 500 frames con hop 250 y se promedian
 * los embeddings resultantes.
 */
class SpeakerEmbedding(
    context: Context,
    modeloEnAssets: String = "wespeaker_emb_fp16.tflite"
) {
    private val numFrames = 500
    private val numMelBins = 80
    private val dimEmbedding = 256
    private val hopFrames = 250

    private val interpreter: Interpreter

    init {
        DebugLog.info("WeSpeaker", "Cargando modelo $modeloEnAssets")
        val modelo = cargarModeloDesdeAssets(context, modeloEnAssets)
        val opciones = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(modelo, opciones)
    }

    fun calcular(fbank: Array<FloatArray>): FloatArray {
        if (fbank.isEmpty()) return FloatArray(dimEmbedding)

        val nFramesReales = fbank.size
        val embeddingsVentanas = mutableListOf<FloatArray>()

        if (nFramesReales <= numFrames) {
            // Fragmento corto: repetir frames cíclicamente (np.resize)
            val entrada = empaquetarConRepeticion(fbank, 0, nFramesReales)
            embeddingsVentanas.add(ejecutarInferencia(entrada))
        } else {
            // Fragmento largo: ventana deslizante de 500 con hop 250
            var inicio = 0
            while (inicio + numFrames <= nFramesReales) {
                val entrada = empaquetarVentana(fbank, inicio, numFrames)
                embeddingsVentanas.add(ejecutarInferencia(entrada))
                inicio += hopFrames
            }
            val restantes = nFramesReales - inicio
            if (restantes >= 50) {
                val entrada = empaquetarConRepeticion(fbank, inicio, restantes)
                embeddingsVentanas.add(ejecutarInferencia(entrada))
            }
        }

        if (embeddingsVentanas.isEmpty()) return FloatArray(dimEmbedding)

        val promedio = promediarYNormalizar(embeddingsVentanas)
        return promedio
    }

    private fun empaquetarVentana(fbank: Array<FloatArray>, inicio: Int, cantidad: Int): FloatArray {
        val entrada = FloatArray(cantidad * numMelBins)
        var f = 0
        while (f < cantidad) {
            val fila = fbank[inicio + f]
            var m = 0
            while (m < numMelBins) {
                entrada[f * numMelBins + m] = if (m < fila.size) fila[m] else 0f
                m++
            }
            f++
        }
        return entrada
    }

    /**
     * Repite los frames cíclicamente hasta llenar numFrames.
     * Equivale a np.resize(fbank, (500, 80)).
     */
    private fun empaquetarConRepeticion(fbank: Array<FloatArray>, inicio: Int, cantidad: Int): FloatArray {
        val entrada = FloatArray(numFrames * numMelBins)
        var f = 0
        while (f < numFrames) {
            val frameOrigen = inicio + (f % cantidad)
            val fila = fbank[frameOrigen]
            var m = 0
            while (m < numMelBins) {
                entrada[f * numMelBins + m] = if (m < fila.size) fila[m] else 0f
                m++
            }
            f++
        }
        return entrada
    }

    private fun ejecutarInferencia(entrada: FloatArray): FloatArray {
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var suma = 0.0
        var i = 0
        while (i < entrada.size) {
            val v = entrada[i]
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            suma += v.toDouble()
            i++
        }
        DebugLog.info("WeSpeaker", "  Inferencia: min=$minV max=$maxV media=${suma / entrada.size}")

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

    private fun promediarYNormalizar(embeddings: List<FloatArray>): FloatArray {
        val suma = FloatArray(dimEmbedding)
        for (e in embeddings) {
            var i = 0
            while (i < dimEmbedding) {
                suma[i] += e[i]
                i++
            }
        }
        val divisor = embeddings.size.toFloat()
        var i = 0
        while (i < dimEmbedding) {
            suma[i] /= divisor
            i++
        }
        return l2Normalizar(suma)
    }

    private fun l2Normalizar(v: FloatArray): FloatArray {
        var suma = 0f
        var i = 0
        while (i < v.size) { suma += v[i] * v[i]; i++ }
        val norma = kotlin.math.sqrt(suma)
        if (norma < 1e-10f) return v
        val out = FloatArray(v.size)
        i = 0
        while (i < v.size) { out[i] = v[i] / norma; i++ }
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