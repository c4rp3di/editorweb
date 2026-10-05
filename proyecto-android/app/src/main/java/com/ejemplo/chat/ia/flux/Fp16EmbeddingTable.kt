package com.ejemplo.chat.ia.flux

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Memory-mapped Qwen embedding table. Only the rows requested by a prompt are paged in. */
class Fp16EmbeddingTable(
    private val file: File,
    private val hiddenSize: Int = 2560
) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val channel = raf.channel
    private val mapped: ByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        .order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(channel.size() % (hiddenSize.toLong() * 2L) == 0L) {
            "Tabla Qwen fp16 inválida: ${channel.size()} bytes"
        }
    }

    val rowCount: Long get() = channel.size() / (hiddenSize * 2L)

    fun lookup(ids: IntArray): FloatArray {
        val out = FloatArray(ids.size * hiddenSize)
        val rowBytes = hiddenSize * 2
        ids.forEachIndexed { row, id ->
            require(id >= 0 && id.toLong() < rowCount) { "Token id fuera de rango: $id" }
            mapped.position(id * rowBytes)
            val base = row * hiddenSize
            repeat(hiddenSize) { col -> out[base + col] = halfToFloat(mapped.short) }
        }
        return out
    }

    override fun close() {
        channel.close()
        raf.close()
    }

    private fun halfToFloat(h: Short): Float {
        val x = h.toInt() and 0xffff
        val sign = x ushr 15
        val exp = (x ushr 10) and 0x1f
        val mant = x and 0x3ff
        val bits = when {
            exp == 0 -> {
                if (mant == 0) sign shl 31
                else {
                    var m = mant
                    var e = -14
                    while ((m and 0x400) == 0) { m = m shl 1; e-- }
                    m = m and 0x3ff
                    ((sign shl 31) or ((e + 127) shl 23) or (m shl 13))
                }
            }
            exp == 0x1f -> (sign shl 31) or 0x7f800000 or (mant shl 13)
            else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return Float.fromBits(bits)
    }
}
