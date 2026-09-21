package com.carpe.microlisto.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Escribe un archivo WAV PCM 16-bit mono a partir de los samples que le llegan.
 * Abre el archivo, escribe una cabecera provisional y la reescribe al cerrar con
 * los tamaños reales. Así no hay que saber de antemano cuánto va a durar la grabación.
 */
class WavWriter(
    private val archivo: File,
    private val sampleRate: Int = 16000
) {
    private val canales = 1
    private val bitsPorSample = 16
    private val bytesPorSample = bitsPorSample / 8

    private var fos: FileOutputStream? = null
    private var bytesEscritos: Long = 0

    fun abrir() {
        archivo.parentFile?.mkdirs()
        fos = FileOutputStream(archivo, false)
        escribirCabecera(0)
    }

    /**
     * Recibe samples en formato float [-1.0, 1.0] y los convierte a PCM 16-bit.
     */
    fun escribirFloat(samples: FloatArray, cantidad: Int) {
        val out = fos ?: return
        val bytes = ByteArray(cantidad * bytesPorSample)
        var i = 0
        var j = 0
        while (i < cantidad) {
            var s = samples[i]
            if (s > 1.0f) s = 1.0f
            if (s < -1.0f) s = -1.0f
            val v = (s * 32767f).toInt().toShort()
            bytes[j] = (v.toInt() and 0xFF).toByte()
            bytes[j + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
            i++
            j += bytesPorSample
        }
        out.write(bytes)
        bytesEscritos += bytes.size
    }

    fun cerrar() {
        val out = fos ?: return
        try {
            out.flush()
            out.close()
        } catch (_: Exception) {}
        fos = null
        // Reescribir la cabecera con los tamaños reales
        try {
            RandomAccessFile(archivo, "rw").use { raf ->
                raf.seek(0)
                raf.write(cabecera(bytesEscritos))
            }
        } catch (_: Exception) {}
    }

    private fun escribirCabecera(bytesDatos: Long) {
        fos?.write(cabecera(bytesDatos))
    }

    private fun cabecera(bytesDatos: Long): ByteArray {
        val byteRate = sampleRate * canales * bytesPorSample
        val blockAlign = canales * bytesPorSample
        val tamanoRiff = 36 + bytesDatos

        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(tamanoRiff.toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16) // tamaño del bloque fmt
        bb.putShort(1) // PCM
        bb.putShort(canales.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bitsPorSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(bytesDatos.toInt())
        return bb.array()
    }
}