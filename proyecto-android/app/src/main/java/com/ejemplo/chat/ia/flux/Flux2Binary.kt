package com.ejemplo.chat.ia.flux

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Flux2Binary {
    fun readFloat32(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0) { "Binario f32 inválido: ${file.path}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    fun readBFloat16(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 2 == 0) { "Binario bf16 inválido: ${file.path}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 2) {
            val hi = buffer.short.toInt() and 0xffff
            Float.fromBits(hi shl 16)
        }
    }

    fun readEitherFloat32OrBFloat16(vararg files: File): FloatArray? {
        val f = files.firstOrNull { it.isFile } ?: return null
        return when {
            f.name.endsWith("_bf16.bin") -> readBFloat16(f)
            else -> readFloat32(f)
        }
    }
    fun readBFloat16Range(file: File, elementOffset: Int, count: Int): FloatArray {
        require(elementOffset >= 0 && count >= 0)
        val out = FloatArray(count)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(elementOffset.toLong() * 2L)
            val b = ByteArray(2)
            for (i in 0 until count) {
                raf.readFully(b)
                val bits = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
                out[i] = Float.fromBits(bits shl 16)
            }
        }
        return out
    }

}
