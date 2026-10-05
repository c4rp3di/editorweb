package com.ejemplo.chat.ia.flux

import android.content.Context
import android.graphics.Bitmap
import com.ejemplo.chat.ia.DebugLog
import com.google.ai.edge.litert.Environment
import java.io.File
import java.util.Random
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FLUX.2 [klein] 4B T2I chunk chain.
 *
 * The graph order follows the LiteRT export:
 * Qwen encoder (3 chunks) -> prompt tap interleave -> kc_prep ->
 * kc_double0..1 -> four kc_single chunks -> kc_final -> VAE.
 *
 * The host timestep/guidance table is downloaded as part of the same Gestura
 * model revision; no learned runtime values are synthesized or guessed.
 */
class Flux2KleinGenerator(context: Context) : AutoCloseable {
    private val root = File(context.filesDir, "modelos-imagen/${Flux2Files.MODEL_ID}")
    private val environment = Environment.create()
    private val host = Flux2HostPrep(root)

    // Estado para cancelar y cerrar de forma segura aunque haya una generación en curso.
    /** Aviso de la última generación (p. ej. faltan las estadísticas BN del VAE). */
    @Volatile var lastWarning: String? = null
        private set

    private val lock = Any()
    @Volatile private var cancelled = false
    private var running = false
    private var closeWhenDone = false
    private var closed = false

    /** Aborta la generación en curso en cuanto termine el grafo que se está ejecutando. */
    fun cancel() { cancelled = true }

    private fun checkCancelled() {
        if (cancelled) throw java.util.concurrent.CancellationException("Generación cancelada")
    }

    fun isReady(): Boolean = Flux2Files.isComplete(root)

    fun readinessError(): String? {
        val missing = Flux2Files.missing(root)
        return if (missing.isEmpty()) null else "Faltan archivos FLUX: ${missing.joinToString()}"
    }

    /** Generate one 256×256 image. Must be called off the main thread. */
    fun generate(prompt: String, seed: Long = System.nanoTime(), onProgress: (String, Float) -> Unit): Bitmap {
        synchronized(lock) {
            check(!closed && !closeWhenDone) { "El generador ya está cerrado" }
            check(!running) { "Ya hay una imagen generándose" }
            running = true
            cancelled = false
        }
        try {
            return generateInternal(prompt, seed, onProgress)
        } finally {
            synchronized(lock) {
                running = false
                if (closeWhenDone && !closed) { closed = true; environment.close() }
            }
        }
    }

    private fun generateInternal(prompt: String, seed: Long, onProgress: (String, Float) -> Unit): Bitmap {
        require(prompt.isNotBlank()) { "El prompt está vacío" }
        val error = readinessError()
        require(error == null) { error!! }

        // Unidades de progreso: 3 codificadores + 4 pasos × 8 grafos + VAE.
        val total = 3 + 4 * 8 + 1
        var done = 0
        val t0 = System.nanoTime()
        fun stage(text: String) {
            checkCancelled()
            onProgress(text, done / total.toFloat())
            DebugLog.log("FLUX", "▶ $text")
        }
        fun finished() { done++ }

        stage("Preparando prompt (tokenizer y embeddings)")
        val prep = host.prepare(prompt)
        DebugLog.log("FLUX", "prompt listo · ${DebugLog.mem()}")
        var hidden = prep.tokenEmbeddings
        val taps = ArrayList<FloatArray>(3)
        for (i in 0 until 3) {
            stage("Codificador de texto ${i + 1}/3")
            hidden = ChunkRunner.gpu(
                environment,
                "ke_enc$i.tflite",
                root,
                listOf(hidden, prep.encMask, prep.encCos, prep.encSin)
            )[0]
            taps += hidden
            finished()
        }
        val promptEmbeds = host.buildPromptEmbedsFromTaps(taps)
        var latents = gaussianNoise(Flux2HostPrep.IMAGE_TOKENS * Flux2HostPrep.IMAGE_PACKED_CHANNELS, seed)

        // Solo kc_prep y kc_final (~185 MB) se reutilizan por defecto. Mantener todos los kc_*
        // compilados a la vez ronda los 4 GB y el sistema mata la app por falta de memoria.
        val kcCache = ChunkRunner.Cache(environment, root)
        fun runKc(name: String, inputs: List<FloatArray>): List<FloatArray> =
            if (REUSE_KC_GRAPHS || name in SMALL_KC_GRAPHS) kcCache.run(name, inputs)
            else ChunkRunner.gpu(environment, name, root, inputs)

        try {
            for (step in 0 until 4) {
                val tag = "Paso ${step + 1}/4"
                val temb = host.readTimestepEmbedding(prep.sigmas[step] * 1000f)
                stage("$tag · preparación")
                var prepOut = runKc(
                    "kc_prep.tflite",
                    listOf(latents, promptEmbeds, temb)
                )
                finished()
                var image = prepOut[0]
                var text = prepOut[1]
                var modImg = prepOut[2]
                var modTxt = prepOut[3]
                var modSingle = if (prepOut.size > 4) prepOut[4] else null
                require(modSingle != null) { "kc_prep no devolvió mod_single" }

                for (i in 0 until 2) {
                    stage("$tag · bloque doble ${i + 1}/2")
                    val o = runKc(
                        "kc_double$i.tflite",
                        listOf(image, text, prep.ditCos, prep.ditSin, modImg, modTxt)
                    )
                    require(o.size >= 2) { "kc_double$i devolvió menos de dos tensores" }
                    image = o[0]
                    text = o[1]
                    finished()
                }

                var joint = FloatArray(text.size + image.size)
                text.copyInto(joint, 0)
                image.copyInto(joint, text.size)
                for (i in 0 until 4) {
                    stage("$tag · bloque simple ${i + 1}/4")
                    joint = runKc(
                        "kc_single$i.tflite",
                        listOf(joint, prep.ditCos, prep.ditSin, modSingle)
                    )[0]
                    finished()
                }
                stage("$tag · salida")
                val pred = runKc(
                    "kc_final.tflite",
                    listOf(joint, temb)
                )[0]
                require(pred.size == latents.size) {
                    "kc_final produjo ${pred.size} valores; se esperaban ${latents.size}"
                }
                val stepDelta = prep.dsigma[step]
                for (i in latents.indices) latents[i] += stepDelta * pred[i]
                finished()
            }
        } finally {
            // Libera la memoria de GPU de los kc_* antes de cargar el VAE.
            kcCache.close()
        }

        stage("Decodificando imagen (VAE)")
        val bn = Flux2VaeStats.load(root)
        lastWarning = if (bn == null) "sin estadísticas BN del VAE: colores aproximados" else null
        if (bn == null) DebugLog.log("FLUX", "⚠ faltan las estadísticas BN del VAE")
        val latent = toVaeLatent(latents, bn)
        val pixels = ChunkRunner.gpu(
            environment,
            "kv_vae.tflite",
            root,
            listOf(latent)
        )[0]
        finished()
        onProgress("Imagen lista", 1f)
        DebugLog.log("FLUX", "✔ imagen generada en ${(System.nanoTime() - t0) / 1_000_000_000} s")
        return pixelsToBitmap(pixels)
    }

    private fun toVaeLatent(packed: FloatArray, bn: Flux2VaeStats.Stats?): FloatArray {
        require(packed.size == Flux2HostPrep.IMAGE_TOKENS * Flux2HostPrep.IMAGE_PACKED_CHANNELS)

        // 1) Unpack: tokens [256,128] -> planos [128,16,16], con la desnormalización BatchNorm
        //    por canal empaquetado: x * std + mean (antes del unpatchify, como en diffusers).
        val planes = FloatArray(Flux2HostPrep.IMAGE_PACKED_CHANNELS * 16 * 16)
        for (token in 0 until 256) {
            val h = token / 16
            val w = token % 16
            for (pc in 0 until 128) {
                val v = packed[token * 128 + pc]
                planes[pc * 256 + h * 16 + w] = if (bn != null) v * bn.std[pc] + bn.mean[pc] else v
            }
        }

        // 2) Unpatchify 2×2: 128 canales empaquetados -> 32 canales a 32×32.
        //    Orden de diffusers (_patchify_latents): pc = c*4 + (dh*2 + dw).
        val out = FloatArray(32 * 32 * 32)
        for (h in 0 until 16) {
            for (w in 0 until 16) {
                val spatial = h * 16 + w
                for (pc in 0 until 128) {
                    val c = if (PACKED_CHANNEL_C_MAJOR) pc ushr 2 else pc and 31
                    val patch = if (PACKED_CHANNEL_C_MAJOR) pc and 3 else pc ushr 5
                    val dh = patch ushr 1
                    val dw = patch and 1
                    val dst = c * 1024 + (h * 2 + dh) * 32 + (w * 2 + dw)
                    out[dst] = planes[pc * 256 + spatial]
                }
            }
        }
        return out
    }

    private fun pixelsToBitmap(pixels: FloatArray): Bitmap {
        val plane = 256 * 256
        require(pixels.size >= 3 * plane) { "El VAE no devolvió una imagen RGB 256×256" }
        val colors = IntArray(plane)
        for (i in 0 until plane) {
            val r = (((pixels[i].coerceIn(-1f, 1f) + 1f) * 127.5f).toInt()).coerceIn(0, 255)
            val g = (((pixels[plane + i].coerceIn(-1f, 1f) + 1f) * 127.5f).toInt()).coerceIn(0, 255)
            val b = (((pixels[2 * plane + i].coerceIn(-1f, 1f) + 1f) * 127.5f).toInt()).coerceIn(0, 255)
            colors[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(colors, 256, 256, Bitmap.Config.ARGB_8888)
    }

    private fun gaussianNoise(size: Int, seed: Long): FloatArray {
        val random = Random(seed)
        val out = FloatArray(size)
        var i = 0
        while (i < size) {
            val u1 = (random.nextDouble().coerceAtLeast(1e-12))
            val u2 = random.nextDouble()
            val radius = sqrt(-2.0 * ln(u1))
            val angle = 2.0 * Math.PI * u2
            out[i++] = (radius * cos(angle)).toFloat()
            if (i < size) out[i++] = (radius * sin(angle)).toFloat()
        }
        return out
    }

    /** Cierra el generador. Si hay una generación en curso, la cancela y cierra al terminar el grafo actual. */
    override fun close() {
        synchronized(lock) {
            cancelled = true
            if (running) { closeWhenDone = true; return }
            if (!closed) { closed = true; environment.close() }
        }
    }

    companion object {
        /**
         * true: compila TODOS los kc_* una vez y los reutiliza en los 4 pasos (más rápido, pero ~4 GB
         * residentes: probable causa de que el sistema cerrase la app en v4-v6). Déjalo en false.
         */
        const val REUSE_KC_GRAPHS = false
        private val SMALL_KC_GRAPHS = setOf("kc_prep.tflite", "kc_final.tflite")

        /**
         * true: canal empaquetado = c*4 + parche (orden de diffusers). false: parche*32 + c
         * (orden que tenía el código original). Si la imagen sale como mosaico de ruido, prueba el otro.
         */
        const val PACKED_CHANNEL_C_MAJOR = true
    }
}
