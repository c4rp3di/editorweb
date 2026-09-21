package com.carpe.microlisto.vad

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val entrada = FloatArray(tamanoEntrada)
        System.arraycopy(bufferContexto, 0, entrada, 0, tamanoContexto)
        System.arraycopy(frame512, 0, entrada, tamanoContexto, tamanoFrame)

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
                val salidaOpt = resultado.get("output")
                val salida = if (salidaOpt.isPresent) salidaOpt.get() else null
                val probabilidad = (salida?.value as? Array<*>)?.let { arr ->
                    val fila = arr[0] as? FloatArray
                    fila?.get(0) ?: 0f
                } ?: 0f

                val stateNOpt = resultado.get("stateN")
                val stateN = if (stateNOpt.isPresent) stateNOpt.get()?.value as? Array<*> else null
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