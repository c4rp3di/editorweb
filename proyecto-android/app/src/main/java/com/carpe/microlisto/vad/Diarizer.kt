package com.carpe.microlisto.vad

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.carpe.microlisto.debug.DebugLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SegmentoDiarizado(
    val hablanteId: Int,
    val inicioMs: Long,
    val finMs: Long
)

class Diarizer(
    context: Context,
    private val modeloAssets: String = "pyannote_seg30.onnx"
) {
    private val sampleRate = 16000
    private val ventanaMuestras = 10 * sampleRate
    private val numClases = 7
    private val frameSegMs = 10000L / 589L
    private val timeoutVentanaMs = 60_000L
    private val frameShiftMuestras = 160  // 10 ms

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val embedder: SpeakerEmbedding
    private val fbank: Fbank = Fbank()

    private val nombreEntrada = "waveform"
    private val nombreSalida = "powerset"

    init {
        DebugLog.info("Diarizer", "Cargando modelo $modeloAssets")
        val modeloBytes = context.assets.open(modeloAssets).use { it.readBytes() }
        val opciones = OrtSession.SessionOptions().apply {
            try {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            } catch (_: Exception) {}
        }
        session = env.createSession(modeloBytes, opciones)
        embedder = SpeakerEmbedding(context)
    }

    fun diarizar(audio: FloatArray): List<SegmentoDiarizado> {
        DebugLog.info("Diarizer", "Inicio diarización: ${audio.size} muestras (${audio.size / sampleRate} s)")

        if (audio.size < sampleRate) {
            DebugLog.warn("Diarizer", "Audio demasiado corto (<1s)")
            return emptyList()
        }

        // Fbank global una sola vez
        val tFbankIni = System.currentTimeMillis()
        val fbankCompleto = fbank.calcular(audio)
        val tFbankFin = System.currentTimeMillis()
        DebugLog.info("Diarizer", "Fbank completo: ${fbankCompleto.size} frames en ${tFbankFin - tFbankIni} ms")

        if (fbankCompleto.isEmpty()) return emptyList()

        // Segmentación por ventanas deslizantes
        val hopMuestras = ventanaMuestras / 2
        val ventanas = mutableListOf<Pair<Int, FloatArray>>()
        var inicio = 0
        while (inicio + ventanaMuestras <= audio.size) {
            ventanas.add(inicio to audio.copyOfRange(inicio, inicio + ventanaMuestras))
            inicio += hopMuestras
        }
        if (inicio < audio.size) {
            val restante = FloatArray(ventanaMuestras)
            val n = audio.size - inicio
            System.arraycopy(audio, inicio, restante, 0, n)
            ventanas.add(inicio to restante)
        }
        DebugLog.info("Diarizer", "Ventanas generadas: ${ventanas.size}")

        data class Fragmento(val inicioMs: Long, val finMs: Long, val frameIni: Int, val frameFin: Int)
        val fragmentos = mutableListOf<Fragmento>()

        var numVentana = 0
        for ((inicioVentana, ventanaAudio) in ventanas) {
            numVentana++
            val activaciones = inferirSegmentacionConTimeout(ventanaAudio)

            val histograma = IntArray(numClases)
            for (clase in activaciones) {
                if (clase in 0 until numClases) histograma[clase]++
            }
            DebugLog.info("Diarizer", "Ventana $numVentana: ${histograma.contentToString()}")

            var i = 0
            while (i < activaciones.size) {
                val clase = activaciones[i]
                if (clase in 1..3) {
                    var j = i
                    while (j < activaciones.size && activaciones[j] == clase) j++
                    val durMs = (j - i) * frameSegMs
                    // Subimos el mínimo a 2 segundos para reducir fragmentos basura
                    if (durMs >= 2000L) {
                        val inicioMs = inicioVentana * 1000L / sampleRate + (i * frameSegMs)
                        val finMs = inicioVentana * 1000L / sampleRate + (j * frameSegMs)
                        val frameIniGlobal = (inicioMs * sampleRate / 1000L / frameShiftMuestras).toInt()
                        val frameFinGlobal = (finMs * sampleRate / 1000L / frameShiftMuestras).toInt()
                            .coerceAtMost(fbankCompleto.size)
                        if (frameFinGlobal > frameIniGlobal) {
                            fragmentos.add(Fragmento(inicioMs, finMs, frameIniGlobal, frameFinGlobal))
                        }
                    }
                    i = j
                } else {
                    i++
                }
            }
        }

        DebugLog.info("Diarizer", "Fragmentos >=2s: ${fragmentos.size}")

        if (fragmentos.isEmpty()) {
            DebugLog.warn("Diarizer", "Sin fragmentos extraíbles")
            return emptyList()
        }

        val embeddings = fragmentos.mapIndexed { idx, frag ->
            val t0 = System.currentTimeMillis()
            val nFrames = frag.frameFin - frag.frameIni
            val fbankFrag = Array(nFrames) { k -> fbankCompleto[frag.frameIni + k] }
            val emb = embedder.calcular(fbankFrag)
            val t1 = System.currentTimeMillis()
            DebugLog.info("Diarizer", "  Frag $idx (${nFrames} frames): embedding en ${t1 - t0}ms")
            emb
        }

        val asignaciones = clusteringAglomerativo(embeddings, umbral = 0.7046f)
        val numClusters = asignaciones.toSet().size
        DebugLog.info("Diarizer", "Clusters finales: $numClusters")

        val segmentos = mutableListOf<SegmentoDiarizado>()
        for (idx in fragmentos.indices) {
            val frag = fragmentos[idx]
            val hablante = asignaciones[idx]
            val ultimo = segmentos.lastOrNull()
            if (ultimo != null && ultimo.hablanteId == hablante &&
                frag.inicioMs - ultimo.finMs < 500L) {
                segmentos[segmentos.size - 1] = ultimo.copy(finMs = frag.finMs)
            } else {
                segmentos.add(SegmentoDiarizado(hablante, frag.inicioMs, frag.finMs))
            }
        }
        DebugLog.info("Diarizer", "Segmentos finales: ${segmentos.size}")
        return segmentos
    }

    private fun inferirSegmentacionConTimeout(ventanaAudio: FloatArray): IntArray {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val tarea = Callable { inferirSegmentacion(ventanaAudio) }
            val futuro = executor.submit(tarea)
            return try {
                futuro.get(timeoutVentanaMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                DebugLog.error("Diarizer", "Timeout: ${e.message}")
                futuro.cancel(true)
                IntArray(589)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun inferirSegmentacion(ventanaAudio: FloatArray): IntArray {
        val shape = longArrayOf(1, 1, ventanaMuestras.toLong())
        val fb = ByteBuffer
            .allocateDirect(ventanaMuestras * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        fb.put(ventanaAudio, 0, ventanaMuestras)
        fb.rewind()

        val tInput = OnnxTensor.createTensor(env, fb, shape)
        try {
            val resultado = session.run(mapOf(nombreEntrada to tInput))
            try {
                val outOpt = resultado.get(nombreSalida)
                if (!outOpt.isPresent) return IntArray(589)
                val out = outOpt.get().value as Array<*>
                val tensor = out[0] as Array<*>
                val activaciones = IntArray(tensor.size)
                var i = 0
                while (i < tensor.size) {
                    val fila = tensor[i] as FloatArray
                    var maxIdx = 0
                    var maxVal = fila[0]
                    var k = 1
                    while (k < fila.size) {
                        if (fila[k] > maxVal) {
                            maxVal = fila[k]
                            maxIdx = k
                        }
                        k++
                    }
                    activaciones[i] = maxIdx
                    i++
                }
                return activaciones
            } finally {
                resultado.close()
            }
        } catch (e: Exception) {
            DebugLog.error("Diarizer", "Error inferencia: ${e.message}")
            return IntArray(589)
        } finally {
            tInput.close()
        }
    }

    private fun clusteringAglomerativo(embeddings: List<FloatArray>, umbral: Float): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        if (n == 1) return intArrayOf(0)

        val clusters = mutableListOf<MutableList<Int>>()
        var i = 0
        while (i < n) {
            val nuevo = mutableListOf<Int>()
            nuevo.add(i)
            clusters.add(nuevo)
            i++
        }

        val centroides = mutableListOf<FloatArray>()
        for (e in embeddings) centroides.add(e.copyOf())

        var iteraciones = 0
        while (iteraciones < 100) {
            iteraciones++
            var mejorI = -1
            var mejorJ = -1
            var mejorSimilitud = -2f
            i = 0
            while (i < clusters.size) {
                var j = i + 1
                while (j < clusters.size) {
                    val sim = similitudCoseno(centroides[i], centroides[j])
                    if (sim > mejorSimilitud) {
                        mejorSimilitud = sim
                        mejorI = i
                        mejorJ = j
                    }
                    j++
                }
                i++
            }
            if (mejorI == -1 || mejorSimilitud < umbral) {
                DebugLog.info("Diarizer", "Clustering: parada iter $iteraciones, mejor sim $mejorSimilitud")
                break
            }
            clusters[mejorI].addAll(clusters[mejorJ])
            centroides[mejorI] = calcularCentroide(clusters[mejorI], embeddings)
            clusters.removeAt(mejorJ)
            centroides.removeAt(mejorJ)
        }

        val asignaciones = IntArray(n)
        var c = 0
        while (c < clusters.size) {
            for (idx in clusters[c]) asignaciones[idx] = c
            c++
        }
        return asignaciones
    }

    private fun similitudCoseno(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var i = 0
        while (i < a.size) {
            dot += a[i] * b[i]
            i++
        }
        return dot
    }

    private fun calcularCentroide(indices: List<Int>, embeddings: List<FloatArray>): FloatArray {
        val suma = FloatArray(embeddings[0].size)
        for (idx in indices) {
            val e = embeddings[idx]
            var i = 0
            while (i < e.size) {
                suma[i] = suma[i] + e[i]
                i++
            }
        }
        val divisor = indices.size.toFloat()
        var i = 0
        while (i < suma.size) {
            suma[i] = suma[i] / divisor
            i++
        }
        var norma = 0f
        for (x in suma) norma += x * x
        norma = kotlin.math.sqrt(norma)
        if (norma > 1e-10f) {
            i = 0
            while (i < suma.size) {
                suma[i] = suma[i] / norma
                i++
            }
        }
        return suma
    }

    fun cerrar() {
        try { session.close() } catch (_: Exception) {}
        try { embedder.cerrar() } catch (_: Exception) {}
    }
}