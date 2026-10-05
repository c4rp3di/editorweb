package com.ejemplo.chat.ia.flux

import android.content.Context
import android.graphics.Bitmap
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
    fun generate(prompt: String, seed: Long = System.nanoTime(), onProgress: (String) -> Unit): Bitmap {
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

    private fun generateInternal(prompt: String, seed: Long, onProgress: (String) -> Unit): Bitmap {
        require(prompt.isNotBlank()) { "El prompt está vacío" }
        val error = readinessError()
        require(error == null) { error!! }

        val prep = host.prepare(prompt)
        var hidden = prep.tokenEmbeddings
        val taps = ArrayList<FloatArray>(3)
        for (i in 0 until 3) {
            checkCancelled()
            hidden = ChunkRunner.gpu(
                environment,
                "ke_enc$i.tflite",
                root,
                listOf(hidden, prep.encMask, prep.encCos, prep.encSin)
            )[0]
            taps += hidden
            onProgress("Codificador de texto ${i + 1}/3")
        }
        val promptEmbeds = host.buildPromptEmbedsFromTaps(taps)
        var latents = gaussianNoise(Flux2HostPrep.IMAGE_TOKENS * Flux2HostPrep.IMAGE_PACKED_CHANNELS, seed)

        val kcCache = if (REUSE_KC_GRAPHS) ChunkRunner.Cache(environment, root) else null
        fun runKc(name: String, inputs: List<FloatArray>): List<FloatArray> =
            kcCache?.run(name, inputs) ?: ChunkRunner.gpu(environment, name, root, inputs)

        try {
            for (step in 0 until 4) {
                val temb = host.readTimestepEmbedding(prep.sigmas[step] * 1000f)
                checkCancelled()
                var prepOut = runKc(
                    "kc_prep.tflite",
                    listOf(latents, promptEmbeds, temb)
                )
                var image = prepOut[0]
                var text = prepOut[1]
                var modImg = prepOut[2]
                var modTxt = prepOut[3]
                var modSingle = if (prepOut.size > 4) prepOut[4] else null
                require(modSingle != null) { "kc_prep no devolvió mod_single" }
    
                for (i in 0 until 2) {
                    checkCancelled()
                    val o = runKc(
                        "kc_double$i.tflite",
                        listOf(image, text, prep.ditCos, prep.ditSin, modImg, modTxt)
                    )
                    require(o.size >= 2) { "kc_double$i devolvió menos de dos tensores" }
                    image = o[0]
                    text = o[1]
                    // Modulation tensors are already prepared by kc_prep.
                }
    
                var joint = FloatArray(text.size + image.size)
                text.copyInto(joint, 0)
                image.copyInto(joint, text.size)
                for (i in 0 until 4) {
                    checkCancelled()
                    joint = runKc(
                        "kc_single$i.tflite",
                        listOf(joint, prep.ditCos, prep.ditSin, modSingle)
                    )[0]
                }
                checkCancelled()
                val pred = runKc(
                    "kc_final.tflite",
                    listOf(joint, temb)
                )[0]
                require(pred.size == latents.size) {
                    "kc_final produjo ${pred.size} valores; se esperaban ${latents.size}"
                }
                val stepDelta = prep.dsigma[step]
                for (i in latents.indices) latents[i] += stepDelta * pred[i]
                onProgress("Difusión ${step + 1}/4")
            }
        } finally {
            // Libera la memoria de GPU de los kc_* antes de cargar el VAE.
            kcCache?.close()
        }

        checkCancelled()
        val latent = toVaeLatent(latents)
        val pixels = ChunkRunner.gpu(
            environment,
            "kv_vae.tflite",
            root,
            listOf(latent)
        )[0]
        onProgress("Decodificando VAE")
        return pixelsToBitmap(pixels)
    }

    private fun toVaeLatent(packed: FloatArray): FloatArray {
        require(packed.size == Flux2HostPrep.IMAGE_TOKENS * Flux2HostPrep.IMAGE_PACKED_CHANNELS)

        // Gestura contract: unpack width-128 tokens to [32,32,32] VAE layout.
        val planes = FloatArray(Flux2HostPrep.IMAGE_PACKED_CHANNELS * 16 * 16)
        for (token in 0 until 256) {
            val h = token / 16
            val w = token % 16
            for (pc in 0 until 128) {
                planes[pc * 256 + h * 16 + w] = packed[token * 128 + pc]
            }
        }

        // 2) Unpatchify 2×2: 128 packed channels -> 32 latent channels at 32×32.
        val out = FloatArray(32 * 32 * 32)
        for (h in 0 until 16) {
            for (w in 0 until 16) {
                val spatial = h * 16 + w
                for (pc in 0 until 128) {
                    val c = pc and 31
                    val patch = pc ushr 5
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
         * true: compila cada kc_* una vez y lo reutiliza en los 4 pasos (mucho más rápido, más memoria GPU).
         * Si el móvil se queda sin memoria al generar, ponlo en false.
         */
        const val REUSE_KC_GRAPHS = true
    }
}
