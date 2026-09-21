package com.carpe.microlisto.vad

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Collections

/**
 * Wrapper de Silero VAD v5 (ONNX Runtime).
 *
 * El modelo recibe dos entradas por frame:
 *   input [1, 576]  ->  64 samples del frame anterior (contexto) + 512 samples nuevos
 *   state [2, 1, 128] -> estado LSTM (h, c) que se realimenta
 *   sr    scalar int64 -> 16000
 *
 * Y devuelve:
 *   output [1, 1]     -> probabilidad de voz
 *   stateN [2, 1, 128] -> estado para el siguiente frame
 *
 * Si se alimenta solo con 512 samples y sin el contexto, el modelo no da error
 * pero devuelve siempre probabilidad ~0. No detecta nada. Por eso el buffer de
 * 64 samples del frame anterior es obligatorio.
 */
class SileroVad(
    context: Context,
    modeloEnAssets: String = "silero_vad.onnx"
) {
    private val tamanoFrame = 512
    private val tamanoContexto = 64
    private val tamanoEntrada = tamanoFrame + tamanoContexto

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val bufferContexto = FloatArray(tamanoContexto)
    private val estado = Array(2) { Array(1) { FloatArray(128) } }

    init {
        val modeloBytes = context.assets.open(modeloEnAssets).use { it.readBytes() }
        session = env.createSession(modeloBytes, OrtSession.SessionOptions())
    }

    fun calcularProbabilidad(frame512: FloatArray): Float {
        // Concatenar contexto (64) + frame nuevo (512)
        val entrada = FloatArray(tamanoEntrada)
        System.arraycopy(bufferContexto, 0, entrada, 0, tamanoContexto)
        System.arraycopy(frame512, 0, entrada, tamanoContexto, tamanoFrame)

        // Guardar los últimos 64 samples del frame actual como contexto del siguiente
        System.arraycopy(frame512, tamanoFrame - tamanoContexto, bufferContexto, 0, tamanoContexto)

        val shapeEntrada = longArrayOf(1, tamanoEntrada.toLong())

        val bufEntrada = ByteBuffer.allocateDirect(tamanoEntrada * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        bufEntrada.put(entrada)
        bufEntrada.rewind()

        val estadoPlano = FloatArray(2 * 128)
        System.arraycopy(estado[0][0], 0, estadoPlano, 0, 128)
        System.arraycopy(estado[1][0], 0, estadoPlano, 128, 128)

        val bufEstado = ByteBuffer.allocateDirect(estadoPlano.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        bufEstado.put(estadoPlano)
        bufEstado.rewind()

        val bufSr = ByteBuffer.allocateDirect(8)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        bufSr.put(16000L)
        bufSr.rewind()

        val tInput = OnnxTensor.createTensor(env, bufEntrada, shapeEntrada)
        val tState = OnnxTensor.createTensor(env, bufEstado, longArrayOf(2, 1, 128))
        val tSr = OnnxTensor.createTensor(env, bufSr)

        try {
            val entradas = mapOf(
                "input" to tInput,
                "state" to tState,
                "sr" to tSr
            )
            val resultado = session.run(entradas)
            try {
                val salida = resultado.get("output")
                val probabilidad = (salida?.value as? Array<*>)?.let { arr ->
                    val fila = arr[0] as? FloatArray
                    fila?.get(0) ?: 0f
                } ?: 0f

                val stateN = resultado.get("stateN")?.value as? Array<*>
                if (stateN != null) {
                    actualizarEstado(stateN)
                }
                return probabilidad
            } finally {
                resultado.close()
            }
        } finally {
            tInput.close()
            tState.close()
            tSr.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun actualizarEstado(stateN: Array<*>) {
        try {
            val dim1 = stateN[0] as Array<*>
            val dim2 = dim1[0] as FloatArray
            // dim2 tiene 256 valores: [0..127] = h, [128..255] = c
            System.arraycopy(dim2, 0, estado[0][0], 0, 128)
            System.arraycopy(dim2, 128, estado[1][0], 0, 128)
        } catch (_: Exception) {}
    }

    fun reset() {
        bufferContexto.fill(0f)
        estado.forEach { it.forEach { f -> f.fill(0f) } }
    }

    fun cerrar() {
        try {
            session.close()
        } catch (_: Exception) {}
    }
}