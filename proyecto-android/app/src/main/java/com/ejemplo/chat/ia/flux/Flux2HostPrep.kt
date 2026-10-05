package com.ejemplo.chat.ia.flux

import java.io.File
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * Deterministic host-side preparation for the FLUX.2 klein 4B T2I graph chain.
 * Learned host values (the projected 3072-d timestep embeddings and VAE BN stats)
 * are deliberately loaded from explicit files when present; they are never invented.
 */
class Flux2HostPrep(private val root: File, private val textLength: Int = TEXT_LENGTH) {
    companion object {
        const val TEXT_LENGTH = 512
        const val TEXT_HIDDEN = 2560
        const val DIT_HEAD_DIM = 128
        const val DIT_HEADS = 24
        const val IMAGE_TOKENS = 256
        const val IMAGE_PACKED_CHANNELS = 128
        const val LATENT_CHANNELS = 32
        const val LATENT_SIDE = 32

        private const val QWEN_ROPE_THETA = 1_000_000.0
        private const val FLUX_ROPE_THETA = 2_000.0
    }

    data class PreparedPrompt(
        val tokenEmbeddings: FloatArray, // [1, L, 2560]
        val encMask: FloatArray,          // [1, 32, L, L]
        val encCos: FloatArray,            // [1, 512, 128]
        val encSin: FloatArray,           // [1, 512, 128]
        val ditCos: FloatArray,              // [1, 768, 128]
        val ditSin: FloatArray,              // [1, 768, 128]
        val sigmas: FloatArray,
        val dsigma: FloatArray
    )

    fun prepare(prompt: String): PreparedPrompt {
        require(Flux2Files.isComplete(root)) {
            "FLUX incompleto: faltan ${Flux2Files.missing(root).joinToString() }"
        }
        val tokenizer = Qwen2Tokenizer(root)
        val ids = tokenizer.encode(tokenizer.renderUserPrompt(prompt), textLength)
        val padded = IntArray(textLength) { tokenizer.padId() }
        ids.copyInto(padded, endIndex = minOf(ids.size, textLength))

        val mask = buildExpandedCausalMask(ids.size)
        val qwenRope = qwenRope(textLength)
        val ditRope = ditRope()

        val tokenEmbeddings = Fp16EmbeddingTable(
            File(root, "tokenizer/qwen_embed_fp16.bin")
        ).use { table -> table.lookup(padded) }

        val schedule = schedule4(IMAGE_TOKENS)
        return PreparedPrompt(
            tokenEmbeddings = tokenEmbeddings,
            encMask = mask,
            encCos = qwenRope.first,
            encSin = qwenRope.second,
            ditCos = ditRope.first,
            ditSin = ditRope.second,
            sigmas = schedule.first,
            dsigma = schedule.second
        )
    }

    /** The Gestura host asset is a BF16 lookup table of 3072-wide embeddings.
     * The runtime contract uses the scheduler timestep in the usual 0..1000
     * timestep domain; the table is indexed by the integer timestep.
     */
    fun readTimestepEmbedding(timestep: Float): FloatArray {
        val file = File(root, "host/time_guidance_embed_bf16.bin")
        require(file.isFile) { "Falta ${file.path}" }
        val bytesPerRow = 3072L * 2L
        val rows = file.length() / bytesPerRow
        require(rows > 1000) { "Tabla time/guidance demasiado corta: $rows filas" }
        val row = timestep.coerceIn(0f, 1000f).roundToInt()
        val all = Flux2Binary.readBFloat16Range(file, row * 3072, 3072)
        require(all.size == 3072) { "Embedding temporal inválido" }
        return all
    }

    fun buildPromptEmbedsFromTaps(taps: List<FloatArray>): FloatArray {
        require(taps.size == 3) { "Se esperan 3 taps del encoder Qwen" }
        taps.forEach { require(it.size == textLength * TEXT_HIDDEN) }
        val out = FloatArray(textLength * 7680)
        for (t in 0 until textLength) {
            for (tap in 0 until 3) {
                val src = t * TEXT_HIDDEN
                val dst = t * 7680 + tap * TEXT_HIDDEN
                taps[tap].copyInto(out, dst, src, src + TEXT_HIDDEN)
            }
        }
        return out
    }

    /** [B,32,512,512], 0 when attending to an allowed key and -INF otherwise. */
    private fun buildExpandedCausalMask(actualTokens: Int): FloatArray {
        val heads = 32
        val seq = textLength
        val out = FloatArray(heads * seq * seq)
        val neg = Float.NEGATIVE_INFINITY
        var p = 0
        for (h in 0 until heads) {
            for (q in 0 until seq) {
                for (k in 0 until seq) {
                    // Padding positions still need a finite attention distribution;
                    // only future positions and padded keys are suppressed.
                    out[p++] = if (k <= q && k < actualTokens) 0f else neg
                }
            }
        }
        return out
    }

    /** Qwen3 4B: head_dim 128, full rotary, half-split cos/sin. */
    private fun qwenRope(seq: Int): Pair<FloatArray, FloatArray> {
        val half = DIT_HEAD_DIM / 2
        val cosOut = FloatArray(seq * DIT_HEAD_DIM)
        val sinOut = FloatArray(seq * DIT_HEAD_DIM)
        for (pos in 0 until seq) {
            for (i in 0 until half) {
                val inv = 1.0 / exp(ln(QWEN_ROPE_THETA) * (2.0 * i / DIT_HEAD_DIM))
                val phase = pos * inv
                val c = cos(phase).toFloat()
                val s = sin(phase).toFloat()
                cosOut[pos * DIT_HEAD_DIM + i] = c
                sinOut[pos * DIT_HEAD_DIM + i] = s
                cosOut[pos * DIT_HEAD_DIM + half + i] = c
                sinOut[pos * DIT_HEAD_DIM + half + i] = s
            }
        }
        return cosOut to sinOut
    }

    /** 4-axis Flux2 rotary IDs: (T,H,W,L), axes dims [32,32,32,32]. */
    private fun ditRope(): Pair<FloatArray, FloatArray> {
        val text = textLength
        val image = IMAGE_TOKENS
        val total = text + image
        val cosOut = FloatArray(total * DIT_HEAD_DIM)
        val sinOut = FloatArray(total * DIT_HEAD_DIM)
        val axesDims = intArrayOf(32, 32, 32, 32)

        fun write(index: Int, t: Int, h: Int, w: Int, l: Int) {
            val coords = intArrayOf(t, h, w, l)
            var offset = 0
            for (axis in axesDims.indices) {
                val d = axesDims[axis]
                val half = d / 2
                val coord = coords[axis]
                for (i in 0 until half) {
                    val inv = 1.0 / exp(ln(FLUX_ROPE_THETA) * (2.0 * i / d))
                    val phase = coord * inv
                    val c = cos(phase).toFloat()
                    val s = sin(phase).toFloat()
                    cosOut[index * DIT_HEAD_DIM + offset + i] = c
                    sinOut[index * DIT_HEAD_DIM + offset + i] = s
                    cosOut[index * DIT_HEAD_DIM + offset + half + i] = c
                    sinOut[index * DIT_HEAD_DIM + offset + half + i] = s
                }
                offset += d
            }
        }

        for (i in 0 until text) write(i, 0, 0, 0, i)
        for (i in 0 until image) {
            val h = i / 16
            val w = i % 16
            write(text + i, 0, h, w, 0)
        }
        return cosOut to sinOut
    }

    /** Exact FLUX.2 four-step empirical schedule used by the reference sampler. */
    fun schedule4(imageSeqLen: Int = IMAGE_TOKENS): Pair<FloatArray, FloatArray> {
        val numSteps = 4
        val a1 = 8.73809524e-05
        val b1 = 1.89833333
        val a2 = 0.00016927
        val b2 = 0.45666666
        val mu = if (imageSeqLen > 4300) {
            a2 * imageSeqLen + b2
        } else {
            val m200 = a2 * imageSeqLen + b2
            val m10 = a1 * imageSeqLen + b1
            val a = (m200 - m10) / 190.0
            val b = m200 - 200.0 * a
            a * numSteps + b
        }

        val raw = doubleArrayOf(1.0, 0.75, 0.5, 0.25, 0.0)
        val shifted = raw.map { t ->
            if (t <= 0.0) 0.0 else exp(mu) / (exp(mu) + (1.0 / t - 1.0))
        }
        val sigmas = FloatArray(4) { shifted[it].toFloat() }
        val dsigma = FloatArray(4) { (shifted[it + 1] - shifted[it]).toFloat() }
        return sigmas to dsigma
    }
}
