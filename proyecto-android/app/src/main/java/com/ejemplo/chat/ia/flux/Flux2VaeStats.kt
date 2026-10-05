package com.ejemplo.chat.ia.flux

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Estadísticas BatchNorm del VAE de FLUX.2 (128 canales empaquetados).
 * El runtime las necesita en el host: latent = unpatchify(unpack(latents) * std + mean).
 *
 * El repo de descarga de la app solo trae host/time_guidance_embed_bf16.bin, así que se leen
 * del VAE original (black-forest-labs/FLUX.2-klein-4B) con peticiones HTTP Range: unos KB, no el
 * archivo entero. Se guardan como [mean×128][std×128] float32 little-endian, con
 * std = sqrt(running_var + eps), eps = batch_norm_eps de vae/config.json (1e-4).
 */
object Flux2VaeStats {
    const val FILE = "host/vae_bn_stats.bin"
    const val CHANNELS = 128
    private const val EPS = 1e-4
    private const val SOURCE =
        "https://huggingface.co/black-forest-labs/FLUX.2-klein-4B/resolve/main/vae/diffusion_pytorch_model.safetensors"

    class Stats(val mean: FloatArray, val std: FloatArray)

    fun isPresent(root: File): Boolean =
        File(root, FILE).let { it.isFile && it.length() == CHANNELS * 2 * 4L }

    fun load(root: File): Stats? {
        if (!isPresent(root)) return null
        val buf = ByteBuffer.wrap(File(root, FILE).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val mean = FloatArray(CHANNELS) { buf.getFloat() }
        val std = FloatArray(CHANNELS) { buf.getFloat() }
        return Stats(mean, std)
    }

    /** Bloqueante: llamar fuera del hilo principal y solo tras confirmación del usuario. */
    fun download(root: File) {
        val headerLen = ByteBuffer.wrap(range(0, 7)).order(ByteOrder.LITTLE_ENDIAN).long
        require(headerLen in 2..16_000_000) { "Cabecera safetensors inválida ($headerLen)" }
        val header = JSONObject(String(range(8, 8 + headerLen - 1), Charsets.UTF_8))
        val dataStart = 8 + headerLen

        fun tensor(suffix: String): FloatArray {
            val key = header.keys().asSequence()
                .firstOrNull { it != "__metadata__" && it.endsWith(suffix) }
                ?: error("El VAE no contiene un tensor «$suffix»")
            val info = header.getJSONObject(key)
            val offsets = info.getJSONArray("data_offsets")
            val dtype = info.getString("dtype")
            val data = range(dataStart + offsets.getLong(0), dataStart + offsets.getLong(1) - 1)
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val bytes = when (dtype) { "F32" -> 4; "F16", "BF16" -> 2; else -> error("dtype no soportado: $dtype") }
            require(data.size / bytes == CHANNELS) { "«$key» tiene ${data.size / bytes} valores; se esperaban $CHANNELS" }
            return FloatArray(CHANNELS) {
                when (dtype) {
                    "F32" -> bb.getFloat()
                    "BF16" -> Float.fromBits((bb.getShort().toInt() and 0xffff) shl 16)
                    else -> halfToFloat(bb.getShort())
                }
            }
        }

        val mean = tensor("running_mean")
        val variance = tensor("running_var")
        val std = FloatArray(CHANNELS) { Math.sqrt(variance[it] + EPS).toFloat() }
        require(mean.all { it.isFinite() } && std.all { it.isFinite() && it > 0f }) { "Estadísticas del VAE no válidas" }

        val out = ByteBuffer.allocate(CHANNELS * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        mean.forEach { out.putFloat(it) }
        std.forEach { out.putFloat(it) }
        val dest = File(root, FILE)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        part.writeBytes(out.array())
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "No se pudo guardar las estadísticas del VAE" }
    }

    private fun range(from: Long, to: Long): ByteArray {
        val c = URL(SOURCE).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("Range", "bytes=$from-$to")
            val code = c.responseCode
            if (code != 206) error("HTTP $code al leer las estadísticas del VAE (se esperaba 206)")
            val data = c.inputStream.use { it.readBytes() }
            require(data.size.toLong() == to - from + 1) { "Lectura parcial del VAE incompleta" }
            return data
        } finally {
            c.disconnect()
        }
    }

    private fun halfToFloat(h: Short): Float {
        val x = h.toInt() and 0xffff
        val exp = (x shr 10) and 0x1f
        val man = x and 0x3ff
        val v = when (exp) {
            0 -> man * Math.pow(2.0, -24.0)
            31 -> if (man == 0) Double.POSITIVE_INFINITY else Double.NaN
            else -> (1.0 + man / 1024.0) * Math.pow(2.0, (exp - 15).toDouble())
        }
        return (if ((x shr 15) == 1) -v else v).toFloat()
    }
}
