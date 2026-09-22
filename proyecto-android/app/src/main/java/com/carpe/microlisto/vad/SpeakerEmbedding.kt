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
 * cortos, se hace padding con el propio audio (replicándolo) en lugar de
 * ceros. Para fragmentos más largos, se desliza una ventana de 500 frames
 * con hop 250 y se promedian los embeddings de todas las ventanas.
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

    /**
     * @param fbank matriz de [N][80] (N puede ser cualquiera)
     * @return embedding L2-normalizado de 256 dims
     */
    fun calcular(fbank: Array<FloatArray>): FloatArray {
        if (fbank.isEmpty()) return FloatArray(dimEmbedding)

        val nFramesReales = fbank.size
        val embeddingsVentanas = mutableListOf<FloatArray>()

        if (nFramesReales <= numFrames) {
            // Fragmento corto: padding replicando el audio
            val entrada = empaquetarConPadding(fbank, 0, nFramesReales)
            val emb = ejecutarInferencia(entrada)
            embeddingsVentanas.add(emb)
        } else {
            // Fragmento largo: ventana deslizante de 500 frames con hop 250
            var inicio = 0
            while (inicio + numFrames <= nFramesReales) {
                val entrada = empaquetarVentanaDirecta(fbank, inicio, numFrames)
                val emb = ejecutarInferencia(entrada)
                embeddingsVentanas.add(emb)
                inicio += hopFrames
            }
            // Última ventana si quedan frames sueltos
            if (inicio < nFramesReales && (nFramesReales - inicio) >= 50) {
                val restantes = nFramesReales - inicio
                if (restantes < numFrames) {
                    val entrada = empaquetarConPadding(fbank, inicio, restantes)
                    val emb = ejecutarInferencia(entrada)
                    embeddingsVentanas.add(emb)
                }
            }
        }

        if (embeddingsVentanas.isEmpty()) return FloatArray(dimEmbedding)

        val promedio = promediarYNormalizar(embeddingsVentanas)
        DebugLog.info("WeSpeaker", "  Embedding final: ${embeddingsVentanas.size} ventana(s) promediada(s), norma=${norma2(promedio)}")
        return promedio
    }

    /**
     * Ventana directa de numFrames empezando en `inicio`.
     */
    private fun empaquetarVentanaDirecta(fbank: Array<FloatArray>, inicio: Int, cantidad: Int): FloatArray {
        val entrada = FloatArray(cantidad * numMelBins)
        for (f in 0 until cantidad) {
            val fila = fbank[inicio + f]
            for (m in 0 until numMelBins) {
                entrada[f * numMelBins + m] = if (m < fila.size) fila[m] else 0f
            }
        }
        return entrada
    }

    /**
     * Fragmento más corto de 500 frames: replicar los frames hasta llenar 500.
     * Esto preserva la información acústica en lugar de añadir ceros.
     */
    private fun empaquetarConPadding(fbank: Array<FloatArray>, inicio: Int, cantidad: Int): FloatArray {
        val entrada = FloatArray(numFrames * numMelBins)
        for (f in 0 until numFrames) {
            val frameOrigen = inicio + (f % cantidad)
            val fila = fbank[frameOrigen]
            for (m in 0 until numMelBins) {
                entrada[f * numMelBins + m] = if (m < fila.size) fila[m] else 0f
            }
        }
        return entrada
    }

    private fun ejecutarInferencia(entrada: FloatArray): FloatArray {
        // Estadísticas para detectar NaNs
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (v in entrada) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        DebugLog.info("WeSpeaker", "  Inferencia: min=$minV max=$maxV")

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
            for (i in 0 until dimEmbedding) suma[i] += e[i]
        }
        val divisor = embeddings.size.toFloat()
        for (i in 0 until dimEmbedding) suma[i] /= divisor
        return l2Normalizar(suma)
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

    private fun norma2(v: FloatArray): Float {
        var suma = 0f
        for (x in v) suma += x * x
        return suma
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