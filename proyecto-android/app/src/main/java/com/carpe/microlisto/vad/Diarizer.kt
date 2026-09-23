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
    val finMs: Long,
    val texto: String = ""
)

class Diarizer(
    context: Context,
    private val modeloAssets: String = "pyannote_seg30.onnx",
    private val numHablantesEsperados: Int = 0,
    private val umbralClustering: Float = 0.55f,
    private val minFragmentoMs: Long = 2000L,
    private val gapFusionMs: Long = 500L,
    private val hopVentanaMs: Int = 5000
) {
    private val sampleRate = 16000
    private val ventanaMuestras = 10 * sampleRate
    private val hopMuestras = hopVentanaMs * sampleRate / 1000
    private val numClases = 7
    private val frameSegMs = 10000L / 589L
    private val timeoutVentanaMs = 60_000L
    private val frameShiftMuestras = 160

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val embedder: SpeakerEmbedding
    private val fbank: Fbank = Fbank()

    private val nombreEntrada = "waveform"
    private val nombreSalida = "powerset"

    init {
        DebugLog.info("Diarizer", "Ajustes: hablantes=$numHablantesEsperados umbral=$umbralClustering minFrag=${minFragmentoMs}ms gap=${gapFusionMs}ms hop=${hopVentanaMs}ms")
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
        DebugLog.info("Diarizer", "Inicio: ${audio.size} muestras (${audio.size / sampleRate} s)")
        if (audio.size < sampleRate) return emptyList()

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

        data class Fragmento(val inicioMs: Long, val finMs: Long, val audio: FloatArray)
        val fragmentos = mutableListOf<Fragmento>()

        var numVentana = 0
        for ((inicioVentana, ventanaAudio) in ventanas) {
            numVentana++
            val activaciones = inferirSegmentacionConTimeout(ventanaAudio)
            var nFrags = 0

            var i = 0
            while (i < activaciones.size) {
                val clase = activaciones[i]
                if (clase in 1..3) {
                    var j = i
                    while (j < activaciones.size && activaciones[j] == clase) j++
                    val durMs = (j - i) * frameSegMs
                    if (durMs >= minFragmentoMs) {
                        nFrags++
                        val inicioMs = inicioVentana * 1000L / sampleRate + (i * frameSegMs)
                        val finMs = inicioVentana * 1000L / sampleRate + (j * frameSegMs)
                        val inicioM = (inicioMs * sampleRate / 1000L).toInt()
                            .coerceAtLeast(0)
                        val finM = (finMs * sampleRate / 1000L).toInt()
                            .coerceAtMost(audio.size)
                        if (finM > inicioM) {
                            val audioFrag = audio.copyOfRange(inicioM, finM)
                            fragmentos.add(Fragmento(inicioMs, finMs, audioFrag))
                        }
                    }
                    i = j
                } else i++
            }
            DebugLog.info("Diarizer", "Ventana $numVentana: $nFrags frags")
        }

        DebugLog.info("Diarizer", "Total fragmentos: ${fragmentos.size}")
        if (fragmentos.isEmpty()) return emptyList()

        // Para cada fragmento: Fbank + CMN por fragmento + embedding
        // (esta es la secuencia que usa WeSpeaker con subseg_cmn=true)
        val embeddings = fragmentos.mapIndexed { idx, frag ->
            val fbankFrag = fbank.calcular(frag.audio)
            val fbankCmn = aplicarCmnPorFragmento(fbankFrag)
            DebugLog.info("Diarizer", "Frag $idx: ${fbankFrag.size} frames")
            embedder.calcular(fbankCmn)
        }

        val asignaciones = clusteringAglomerativo(embeddings)
        val numClusters = asignaciones.toSet().size
        DebugLog.info("Diarizer", "Clusters: $numClusters")

        val segmentos = mutableListOf<SegmentoDiarizado>()
        for (idx in fragmentos.indices) {
            val frag = fragmentos[idx]
            val hablante = asignaciones[idx]
            val ultimo = segmentos.lastOrNull()
            if (ultimo != null && ultimo.hablanteId == hablante &&
                frag.inicioMs - ultimo.finMs < gapFusionMs) {
                segmentos[segmentos.size - 1] = ultimo.copy(finMs = frag.finMs)
            } else {
                segmentos.add(SegmentoDiarizado(hablante, frag.inicioMs, frag.finMs))
            }
        }
        DebugLog.info("Diarizer", "Segmentos: ${segmentos.size}")
        return segmentos
    }

    /**
     * CMN por fragmento: restar la media de cada bin mel a lo largo del tiempo,
     * solo dentro del fragmento. Replica subseg_cmn=true de WeSpeaker.
     */
    private fun aplicarCmnPorFragmento(matriz: Array<FloatArray>): Array<FloatArray> {
        if (matriz.isEmpty()) return matriz
        val numFrames = matriz.size
        val numBins = matriz[0].size

        val medias = FloatArray(numBins)
        var f = 0
        while (f < numFrames) {
            var m = 0
            while (m < numBins) {
                medias[m] += matriz[f][m]
                m++
            }
            f++
        }
        val divisor = numFrames.toFloat()
        var m = 0
        while (m < numBins) {
            medias[m] /= divisor
            m++
        }

        val resultado: Array<FloatArray> = Array(numFrames) { FloatArray(numBins) }
        f = 0
        while (f < numFrames) {
            m = 0
            while (m < numBins) {
                resultado[f][m] = matriz[f][m] - medias[m]
                m++
            }
            f++
        }
        return resultado
    }

    private fun inferirSegmentacionConTimeout(ventanaAudio: FloatArray): IntArray {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val futuro = executor.submit(Callable { inferirSegmentacion(ventanaAudio) })
            return try {
                futuro.get(timeoutVentanaMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
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
                        if (fila[k] > maxVal) { maxVal = fila[k]; maxIdx = k }
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
            DebugLog.error("Diarizer", "Error: ${e.message}")
            return IntArray(589)
        } finally {
            tInput.close()
        }
    }

    private fun clusteringAglomerativo(embeddings: List<FloatArray>): IntArray {
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
        while (iteraciones < 100 && clusters.size > 1) {
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
            if (mejorI == -1) break

            if (numHablantesEsperados > 0) {
                if (clusters.size <= numHablantesEsperados) break
            } else {
                if (mejorSimilitud < umbralClustering) {
                    DebugLog.info("Diarizer", "Clustering auto para en iter $iteraciones, sim $mejorSimilitud")
                    break
                }
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
        while (i < a.size) { dot += a[i] * b[i]; i++ }
        return dot
    }

    private fun calcularCentroide(indices: List<Int>, embeddings: List<FloatArray>): FloatArray {
        val suma = FloatArray(embeddings[0].size)
        for (idx in indices) {
            val e = embeddings[idx]
            var i = 0
            while (i < e.size) { suma[i] = suma[i] + e[i]; i++ }
        }
        val divisor = indices.size.toFloat()
        var i = 0
        while (i < suma.size) { suma[i] = suma[i] / divisor; i++ }
        var norma = 0f
        for (x in suma) norma += x * x
        norma = kotlin.math.sqrt(norma)
        if (norma > 1e-10f) {
            i = 0
            while (i < suma.size) { suma[i] = suma[i] / norma; i++ }
        }
        return suma
    }

    fun cerrar() {
        try { session.close() } catch (_: Exception) {}
        try { embedder.cerrar() } catch (_: Exception) {}
    }
}