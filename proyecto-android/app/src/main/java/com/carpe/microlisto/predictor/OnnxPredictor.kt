package com.carpe.microlisto.predictor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

// Carga un modelo .onnx desde app/src/main/assets/. Copia tu modelo ahí
// (por defecto se busca "modelo.onnx") y adapta forma de entrada/salida a
// tu caso: este ejemplo asume un vector de floats como entrada.
class OnnxPredictor(context: Context, nombreModeloEnAssets: String = "modelo.onnx") {
    private val entorno: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sesion: OrtSession

    init {
        val bytesModelo = context.assets.open(nombreModeloEnAssets).use { it.readBytes() }
        sesion = entorno.createSession(bytesModelo)
    }

    fun predecir(entrada: FloatArray, forma: LongArray): Array<*> {
        val tensorEntrada = OnnxTensor.createTensor(entorno, FloatBuffer.wrap(entrada), forma)
        tensorEntrada.use {
            val nombreEntrada = sesion.inputNames.iterator().next()
            sesion.run(mapOf(nombreEntrada to it)).use { resultado ->
                return resultado[0].value as Array<*>
            }
        }
    }

    fun cerrar() {
        sesion.close()
    }
}
