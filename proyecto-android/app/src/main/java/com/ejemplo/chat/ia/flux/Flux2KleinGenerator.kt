package com.ejemplo.chat.ia.flux

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Environment
import java.io.Closeable
import java.io.File

/** Real FLUX.2 klein 4B four-step LiteRT GPU pipeline, adapted from Google's Android sample. */
class Flux2KleinGenerator(context: Context) : Closeable {
    private val root = File(context.filesDir, "modelos-imagen/flux2-klein-4b")
    private val env = Environment.create()
    init { require(isReady()) { "FLUX.2 klein package is incomplete" } }
    fun isReady(): Boolean = Flux2Files.isComplete(root)

    fun generate(prompt: String, onProgress: (String) -> Unit): Bitmap {
        require(isReady()) { "Descarga primero los 43 archivos de FLUX.2 klein 4B." }
        val b = Flux2Files.Bins(root)
        var hidden = b.inputsEmbeds
        val taps = ArrayList<FloatArray>(3)
        for (i in 0 until 3) {
            hidden = ChunkRunner.gpu(env, "ke_enc$i.tflite", root, listOf(hidden, b.encMask, b.encCos, b.encSin))[0]
            taps += hidden
            onProgress("Codificador ${i + 1}/3")
        }
        val promptEmbeds = FloatArray(512 * 7680)
        for (t in 0 until 512) for (c in 0 until 3) {
            System.arraycopy(taps[c], t * 2560, promptEmbeds, t * 7680 + c * 2560, 2560)
        }
        var latents = b.latents0.copyOf()
        val ts = b.temb.size / 4
        for (step in 0 until 4) {
            val temb = b.temb.copyOfRange(step * ts, (step + 1) * ts)
            var o = ChunkRunner.gpu(env, "kc_prep.tflite", root, listOf(latents, promptEmbeds, temb))
            var image = o[0]; var text = o[1]
            for (i in 0 until 2) {
                o = ChunkRunner.gpu(env, "kc_double$i.tflite", root, listOf(image, text, b.cos, b.sin, o[2], o[3]))
                image = o[0]; text = o[1]
            }
            var joint = text + image
            for (i in 0 until 4) joint = ChunkRunner.gpu(env, "kc_single$i.tflite", root, listOf(joint, b.cos, b.sin, o[4]))[0]
            val pred = ChunkRunner.gpu(env, "kc_final.tflite", root, listOf(joint, temb))[0]
            for (i in latents.indices) latents[i] += b.dsigma[step] * pred[i]
            onProgress("Generación ${step + 1}/4")
        }
        val unpacked = gather(latents, b.unpack)
        val plane = 256
        for (c in 0 until 128) for (i in 0 until 256) unpacked[c * plane + i] = unpacked[c * plane + i] * b.bnStd[c] + b.bnMean[c]
        val latent = gather(unpacked, b.unpatch)
        val pixels = ChunkRunner.gpu(env, "kv_vae.tflite", root, listOf(latent))[0]
        val out = IntArray(256 * 256)
        val p = 256 * 256
        for (i in 0 until p) {
            val r = (((pixels[i].coerceIn(-1f,1f)+1f)*127.5f).toInt())
            val g = (((pixels[p+i].coerceIn(-1f,1f)+1f)*127.5f).toInt())
            val bl = (((pixels[2*p+i].coerceIn(-1f,1f)+1f)*127.5f).toInt())
            out[i] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or bl
        }
        onProgress("Imagen generada")
        return Bitmap.createBitmap(out, 256, 256, Bitmap.Config.ARGB_8888)
    }
    private fun gather(src: FloatArray, idx: IntArray) = FloatArray(idx.size) { src[idx[it]] }
    override fun close() = env.close()
}
