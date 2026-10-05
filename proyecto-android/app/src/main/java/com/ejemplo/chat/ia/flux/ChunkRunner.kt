package com.ejemplo.chat.ia.flux

import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Accelerator
import com.ejemplo.chat.ia.DebugLog
import java.io.File

/** Ejecución de grafos LiteRT por trozos (CPU por defecto; GPU en FP32 para evitar NaN en bloques modulados). */
object ChunkRunner {
    /** Muestrea memoria cada 500 ms mientras dura [block]; deja en el log el último valor antes de un cierre. */
    private fun <T> watched(label: String, block: () -> T): T {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val t = Thread {
            while (running.get()) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                val st = runCatching {
                    File("/proc/self/status").readLines()
                        .filter { it.startsWith("VmRSS") || it.startsWith("VmSwap") }
                        .joinToString(" ") { it.replace(Regex("\\s+"), " ") }
                }.getOrDefault("?")
                val avail = runCatching {
                    File("/proc/meminfo").readLines().firstOrNull { it.startsWith("MemAvailable") }
                        ?.replace(Regex("\\s+"), " ")
                }.getOrNull()
                val psi = runCatching { File("/proc/pressure/memory").readText().lineSequence().firstOrNull() }.getOrNull()
                DebugLog.log("MEMW", "$label · $st · $avail · $psi")
            }
        }.apply { isDaemon = true; start() }
        try { return block() } finally { running.set(false); t.interrupt() }
    }

    enum class Backend { CPU, GPU }

    /**
     * CPU (XNNPACK) por defecto. En la GPU, estos grafos int8 se expanden a FP32: ke_enc0 llegó a
     * 3,5 GB de memoria de GPU y HyperOS/Xiaomi cierra la app al pasar de ~1,5 GB (GpuMemory OOM).
     */
    @Volatile var backend: Backend = Backend.CPU

    private val cpuThreads: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun compile(environment: Environment, name: String, directory: File): CompiledModel {
        val options = if (backend == Backend.GPU) {
            CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP32
                )
            }
        } else {
            CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads)
            }
        }
        val t0 = System.nanoTime()
        val label = if (backend == Backend.GPU) "GPU" else "CPU×$cpuThreads"
        DebugLog.log("GPU", "compilando $name [$label] · ${DebugLog.mem()}")
        System.gc()
        val model = watched("compilando $name") {
            CompiledModel.create(File(directory, name).absolutePath, options, environment)
        }
        DebugLog.log("GPU", "compilado $name en ${(System.nanoTime() - t0) / 1_000_000} ms")
        return model
    }

    private fun execute(model: CompiledModel, name: String, inputs: List<FloatArray>): List<FloatArray> {
        val inputBuffers = model.createInputBuffers()
        val outputBuffers = model.createOutputBuffers()
        try {
            require(inputBuffers.size == inputs.size) {
                "$name esperaba ${inputBuffers.size} entradas; se recibieron ${inputs.size}"
            }
            val expected = inputBuffers.map { runCatching { it.readFloat().size }.getOrDefault(-1) }
            val bad = inputs.indices.filter { expected[it] >= 0 && expected[it] != inputs[it].size }
            require(bad.isEmpty()) {
                "$name: tamaños de entrada incompatibles (esperado/enviado en floats): " +
                    inputs.indices.joinToString { "#$it ${expected[it]}/${inputs[it].size}" }
            }
            inputs.forEachIndexed { index, values -> inputBuffers[index].writeFloat(values) }
            val t0 = System.nanoTime()
            watched("ejecutando $name") { model.run(inputBuffers, outputBuffers) }
            val out = outputBuffers.map { it.readFloat() }
            DebugLog.log("GPU", "ejecutado $name en ${(System.nanoTime() - t0) / 1_000_000} ms · ${DebugLog.mem()}")
            return out
        } finally {
            inputBuffers.forEach { it.close() }
            outputBuffers.forEach { it.close() }
        }
    }

    /** Nº de floats que espera la entrada [index] del grafo (para detectar la longitud real del texto). */
    fun inputFloatCount(environment: Environment, name: String, directory: File, index: Int): Int {
        val model = compile(environment, name, directory)
        try {
            val buffers = model.createInputBuffers()
            try { return buffers[index].readFloat().size } finally { buffers.forEach { it.close() } }
        } finally { model.close() }
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
