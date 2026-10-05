package com.ejemplo.chat.ia.flux

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File

object ChunkRunner {
    fun gpu(env: Environment, name: String, dir: File, inputs: List<FloatArray>): List<FloatArray> {
        val options = CompiledModel.Options(Accelerator.GPU)
        options.gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
        val model = CompiledModel.create(File(dir, name).absolutePath, options, env)
        val ins = model.createInputBuffers()
        val outs = model.createOutputBuffers()
        try {
            inputs.forEachIndexed { i, v -> ins[i].writeFloat(v) }
            model.run(ins, outs)
            return outs.map { it.readFloat() }
        } finally {
            ins.forEach { it.close() }; outs.forEach { it.close() }; model.close()
        }
    }
}
