package com.perplexus.game.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.sqrt

/** Partículas puntuales con mezcla aditiva. Se simulan en el sistema E (rotan con la esfera). */
class ParticleSystem(private val max: Int = RenderConstants.MAX_PARTICLES) {
    private val px = FloatArray(max); private val py = FloatArray(max); private val pz = FloatArray(max)
    private val vx = FloatArray(max); private val vy = FloatArray(max); private val vz = FloatArray(max)
    private val life = FloatArray(max); private val maxLife = FloatArray(max)
    private val size = FloatArray(max)
    private val cr = FloatArray(max); private val cg = FloatArray(max); private val cb = FloatArray(max)
    private var count = 0
    private val rnd = Random(12345)

    private val data = FloatArray(max * FLOATS)
    private var buffer: FloatBuffer? = null
    private var vao = 0; private var vbo = 0

    val activeCount: Int get() = count

    fun create() {
        buffer = ByteBuffer.allocateDirect(max * FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, max * FLOATS * 4, null, GLES30.GL_DYNAMIC_DRAW)
        val s = FLOATS * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, s, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, s, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, s, 16)
        GLES30.glBindVertexArray(0)
    }

    fun clear() { count = 0 }

    /** Ráfaga de [n] partículas desde (x,y,z), sesgadas hacia la normal (nx,ny,nz). */
    fun burst(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, n: Int, speed: Float, r: Float, g: Float, b: Float, sizeScale: Float = 1f, lifeTime: Float = 0.6f) {
        for (i in 0 until n) {
            if (count >= max) return
            val k = count++
            var dx = rnd.nextFloat() * 2f - 1f; var dy = rnd.nextFloat() * 2f - 1f; var dz = rnd.nextFloat() * 2f - 1f
            val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            dx = dx / l + nx * 0.8f; dy = dy / l + ny * 0.8f; dz = dz / l + nz * 0.8f
            val sp = speed * (0.4f + rnd.nextFloat() * 0.8f)
            px[k] = x; py[k] = y; pz[k] = z
            vx[k] = dx * sp; vy[k] = dy * sp; vz[k] = dz * sp
            maxLife[k] = lifeTime * (0.6f + rnd.nextFloat() * 0.6f); life[k] = maxLife[k]
            size[k] = (0.012f + rnd.nextFloat() * 0.012f) * sizeScale
            cr[k] = r; cg[k] = g; cb[k] = b
        }
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                // swap-remove
                val last = count - 1
                if (i != last) copy(last, i)
                count--
                continue
            }
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            val damp = 1f - 2.5f * dt
            vx[i] *= damp; vy[i] *= damp; vz[i] *= damp
            i++
        }
    }

    private fun copy(from: Int, to: Int) {
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from]
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        life[to] = life[from]; maxLife[to] = maxLife[from]; size[to] = size[from]
        cr[to] = cr[from]; cg[to] = cg[from]; cb[to] = cb[from]
    }

    fun draw(shader: Shader) {
        if (count == 0 || vao == 0) return
        for (i in 0 until count) {
            val o = i * FLOATS
            val a = life[i] / maxLife[i]
            data[o] = px[i]; data[o + 1] = py[i]; data[o + 2] = pz[i]
            data[o + 3] = size[i]
            data[o + 4] = cr[i]; data[o + 5] = cg[i]; data[o + 6] = cb[i]; data[o + 7] = a
        }
        val buf = buffer ?: return
        buf.clear(); buf.put(data, 0, count * FLOATS); buf.position(0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * FLOATS * 4, buf)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (vao != 0) { GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0); vao = 0 }
        if (vbo != 0) { GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0); vbo = 0 }
    }

    companion object { private const val FLOATS = 8 }
}
