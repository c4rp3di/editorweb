package com.carpe.microlisto.vad

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Segmento etiquetado por hablante, en milisegundos.
 */
data class SegmentoDiarizado(
    val hablanteId: Int,
    val inicioMs: Long,
    val finMs: Long
)

/**
 * Diarización de hablantes.
 *
 * Pipeline:
 *   1. Ventana deslizante de 10 s sobre el audio completo.
 *   2. Por ventana, inferencia con pyannote_seg30.onnx -> [1, 589, 7] log-probs.
 *   3. Argmax por frame para saber qué hablante(s) están activos en cada instante.
 *   4. Extraer fragmentos de "solo habla" (un único hablante activo) de al menos 1 s.
 *   5. Calcular embedding de cada fragmento con SpeakerEmbedding.
 *   6. Clustering aglomerativo (distancia coseno, umbral 0.7046).
 *   7. Fusionar fragmentos con el mismo ID y devolver segmentos finales.
 *
 * Nota: el modelo pyannote_seg30 espera 10 s exactos de audio (160.000 muestras
 * a 16 kHz). Si el audio no es múltiplo, se rellena con ceros.
 */
class Diarizer(
    context: Context,
    private val modeloAssets: String = "pyannote_seg30.onnx"
) {
    private val sampleRate = 16000
    private val ventanaMuestras = 10 * sampleRate // 160.000
    private val numClases = 7  // powerset: silencio, 3 individuales, 3 pares
    private val frameSegMs = 10000L / 589L  // ~16.98 ms por frame

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val embedder: SpeakerEmbedding
    private val fbank: Fbank = Fbank()

    init {
        val modeloBytes = context.assets.open(modeloAssets).use { it.readBytes() }
        session = env.createSession(modeloBytes, OrtSession.SessionOptions())
        embedder = SpeakerEmbedding(context)
    }

    /**
     * @param audio muestras float [-1, 1] a 16 kHz
     * @return lista de segmentos etiquetados por hablante
     */
    fun diarizar(audio: FloatArray): List<SegmentoDiarizado> {
        if (audio.size < sampleRate) return emptyList()

        // Paso 1: ventana deslizante de 10 s, sin solapar (o con poco solape)
        val hopMuestras = ventanaMuestras / 2  // 5 s de hop
        val ventanas = mutableListOf<Pair<Int, FloatArray>>()
        var inicio = 0
        while (inicio + ventanaMuestras <= audio.size) {
            val v = audio.copyOfRange(inicio, inicio + ventanaMuestras)
            ventanas.add(inicio to v)
            inicio += hopMuestras
        }
        // Última ventana parcial (rellenar con ceros)
        if (inicio < audio.size) {
            val restante = FloatArray(ventanaMuestras)
            val n = audio.size - inicio
            System.arraycopy(audio, inicio, restante, 0, n)
            ventanas.add(inicio to restante)
        }

        // Paso 2-3: inferencia de segmentación por ventana y extracción de
        // fragmentos de "solo habla" (single-speaker).
        data class Fragmento(val inicioAbs: Long, val finAbs: Long, val audio: FloatArray)
        val fragmentos = mutableListOf<Fragmento>()

        for ((inicioVentana, ventanaAudio) in ventanas) {
            val activaciones = inferirSegmentacion(ventanaAudio) // Array[589] de clase argmax
            // Buscar tramos con clase 1, 2 o 3 (un único hablante)
            var i = 0
            while (i < activaciones.size) {
                val clase = activaciones[i]
                if (clase in 1..3) {
                    var j = i
                    while (j < activaciones.size && activaciones[j] == clase) j++
                    val durMs = (j - i) * frameSegMs
                    if (durMs >= 1000L) { // al menos 1 s
                        val inicioMs = inicioVentana * 1000L / sampleRate + (i * frameSegMs)
                        val finMs = inicioVentana * 1000L / sampleRate + (j * frameSegMs)
                        val inicioM = i * 160 // 10 ms = 160 muestras
                        val finM = minOf(j * 160, ventanaAudio.size)
                        if (finM > inicioM) {
                            val audioFrag = ventanaAudio.copyOfRange(inicioM, finM)
                            fragmentos.add(Fragmento(inicioMs, finMs, audioFrag))
                        }
                    }
                    i = j
                } else {
                    i++
                }
            }
        }

        if (fragmentos.isEmpty()) return emptyList()

        // Paso 5: calcular embeddings de cada fragmento
        val embeddings = fragmentos.map { frag ->
            val f = fbank.calcular(frag.audio)
            if (f.isEmpty()) FloatArray(256) else embedder.calcular(f)
        }

        // Paso 6: clustering aglomerativo sobre embeddings con distancia coseno
        val asignaciones = clusteringAglomerativo(embeddings, umbral = 0.7046f)

        // Paso 7: construir segmentos finales, fusionando fragmentos consecutivos
        // con el mismo hablante
        val segmentos = mutableListOf<SegmentoDiarizado>()
        for (idx in fragmentos.indices) {
            val frag = fragmentos[idx]
            val hablante = asignaciones[idx]
            val ultimo = segmentos.lastOrNull()
            if (ultimo != null && ultimo.hablanteId == hablante &&
                frag.inicioAbs - ultimo.finMs < 500L) {
                segmentos[segmentos.size - 1] = ultimo.copy(finMs = frag.finAbs)
            } else {
                segmentos.add(SegmentoDiarizado(hablante, frag.inicioAbs, frag.finAbs))
            }
        }
        return segmentos
    }

    /**
     * Ejecuta el modelo de segmentación sobre una ventana de 10 s y devuelve,
     * por cada uno de los 589 frames, la clase argmax.
     */
    private fun inferirSegmentacion(ventanaAudio: FloatArray): IntArray {
        val shape = longArrayOf(1, 1, ventanaMuestras.toLong())
        val bufEntrada = ByteBuffer
            .allocateDirect(ventanaMuestras * 4)
            .order(ByteOrder.nativeOrder())
        val fb = bufEntrada.asFloatBuffer()
        fb.put(ventanaAudio, 0, ventanaMuestras)
        fb.rewind()

        val tInput = OnnxTensor.createTensor(env, bufEntrada, shape)
        try {
            val resultado = session.run(mapOf("input" to tInput))
            try {
                val outOpt = resultado.get("output")
                if (!outOpt.isPresent) return IntArray(589) { 0 }
                val out = outOpt.get().value as Array<*>
                val tensor = out[0] as Array<*> // [589, 7]
                val activaciones = IntArray(tensor.size)
                for (i in tensor.indices) {
                    val fila = tensor[i] as FloatArray
                    var maxIdx = 0
                    var maxVal = fila[0]
                    for (k in 1 until fila.size) {
                        if (fila[k] > maxVal) { maxVal = fila[k]; maxIdx = k }
                    }
                    activaciones[i] = maxIdx
                }
                return activaciones
            } finally {
                resultado.close()
            }
        } finally {
            tInput.close()
        }
    }

    /**
     * Clustering aglomerativo con enlace de centroide.
     * @return por cada embedding, su ID de cluster (0, 1, 2, ...)
     */
    private fun clusteringAglomerativo(embeddings: List<FloatArray>, umbral: Float): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        if (n == 1) return intArrayOf(0)

        // Cada punto empieza en su propio cluster
        val clusters = mutableListOf<MutableList<Int>>()
        for (i in 0 until n) clusters.add(mutableListOf(i))

        // Centroides
        val centroides = mutableListOf<FloatArray>()
        for (e in embeddings) centroides.add(e.copyOf())

        while (true) {
            var mejorI = -1
            var mejorJ = -1
            var mejorSimilitud = -2f
            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val sim = similitudCoseno(centroides[i], centroides[j])
                    if (sim > mejorSimilitud) {
                        mejorSimilitud = sim
                        mejorI = i
                        mejorJ = j
                    }
                }
            }
            if (mejorI == -1 || mejorSimilitud < umbral) break

            // Fusionar mejorJ en mejorI
            clusters[mejorI].addAll(clusters[mejorJ])
            centroides[mejorI] = calcularCentroide(clusters[mejorI], embeddings)
            clusters.removeAt(mejorJ)
            centroides.removeAt(mejorJ)
        }

        // Asignar IDs
        val asignaciones = IntArray(n)
        for (c in clusters.indices) {
            for (idx in clusters[c]) asignaciones[idx] = c
        }
        return asignaciones
    }

    private fun similitudCoseno(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot  // ya son L2-normalizados
    }

    private fun calcularCentroide(indices: List<Int>, embeddings: List<FloatArray>): FloatArray {
        val suma = FloatArray(embeddings[0].size)
        for (idx in indices) {
            val e = embeddings[idx]
            for (i in e.indices) suma[i] += e[i]
        }
        for (i in suma.indices) suma[i] /= indices.size
        // Re-normalizar
        var norma = 0f
        for (x in suma) norma += x * x
        norma = kotlin.math.sqrt(norma)
        if (norma > 1e-10f) {
            for (i in suma.indices) suma[i] /= norma
        }
        return suma
    }

    fun cerrar() {
        try { session.close() } catch (_: Exception) {}
        try { embedder.cerrar() } catch (_: Exception) {}
    }
}