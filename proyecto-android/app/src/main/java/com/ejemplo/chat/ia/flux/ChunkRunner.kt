package com.ejemplo.chat.ia.flux

import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Accelerator
import java.io.File

/** GPU execution of LiteRT chunks, using FP32 to avoid NaNs in modulated blocks. */
object ChunkRunner {
    private fun compile(environment: Environment, name: String, directory: File): CompiledModel {
        val options = CompiledModel.Options(Accelerator.GPU)
        options.gpuOptions = CompiledModel.GpuOptions(
            precision = CompiledModel.GpuOptions.Precision.FP32
        )
        return CompiledModel.create(File(directory, name).absolutePath, options, environment)
    }

    private fun execute(model: CompiledModel, name: String, inputs: List<FloatArray>): List<FloatArray> {
        val inputBuffers = model.createInputBuffers()
        val outputBuffers = model.createOutputBuffers()
        try {
            require(inputBuffers.size == inputs.size) {
                "$name esperaba ${inputBuffers.size} entradas; se recibieron ${inputs.size}"
            }
            inputs.forEachIndexed { index, values -> inputBuffers[index].writeFloat(values) }
            model.run(inputBuffers, outputBuffers)
            return outputBuffers.map { it.readFloat() }
        } finally {
            inputBuffers.forEach { it.close() }
            outputBuffers.forEach { it.close() }
        }
    }

    /** One-shot: compila, ejecuta y libera. Úsalo para grafos que se ejecutan una sola vez. */
    fun gpu(
        environment: Environment,
        name: String,
        directory: File,
        inputs: List<FloatArray>
    ): List<FloatArray> {
        val model = compile(environment, name, directory)
        try {
            return execute(model, name, inputs)
        } finally {
            model.close()
        }
    }

    /**
     * Compila cada grafo la primera vez y lo reutiliza en las siguientes llamadas.
     * Pensado para los grafos kc_* que se repiten en los 4 pasos de difusión.
     * close() libera todos los modelos compilados.
     */
    class Cache(private val environment: Environment, private val directory: File) : AutoCloseable {
        private val models = HashMap<String, CompiledModel>()

        fun run(name: String, inputs: List<FloatArray>): List<FloatArray> {
            val model = models.getOrPut(name) { compile(environment, name, directory) }
            return execute(model, name, inputs)
        }

        override fun close() {
            models.values.forEach { runCatching { it.close() } }
            models.clear()
        }
    }
}
