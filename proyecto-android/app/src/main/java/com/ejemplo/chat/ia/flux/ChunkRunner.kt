package com.ejemplo.chat.ia.flux

import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Accelerator
import java.io.File

/** One-shot GPU execution of a LiteRT chunk, using FP32 to avoid NaNs in modulated blocks. */
object ChunkRunner {
    fun gpu(
        environment: Environment,
        name: String,
        directory: File,
        inputs: List<FloatArray>
    ): List<FloatArray> {
        val options = CompiledModel.Options(Accelerator.GPU)
        options.gpuOptions = CompiledModel.GpuOptions(
            precision = CompiledModel.GpuOptions.Precision.FP32
        )
        val model = CompiledModel.create(File(directory, name).absolutePath, options, environment)
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
            model.close()
        }
    }
}
