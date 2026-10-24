package com.perplexus.game.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Geometría en CPU: vértices intercalados (pos, normal, uv, color) e índices. */
class MeshData {
    var verts = FloatArray(4096)
    var vCount = 0
    var idx = IntArray(8192)
    var iCount = 0

    val vertexCount: Int get() = vCount
    val triangleCount: Int get() = iCount / 3

    fun vertex(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float): Int {
        val need = (vCount + 1) * RenderConstants.FLOATS_PER_VERTEX
        if (need > verts.size) verts = verts.copyOf(maxOf(need, verts.size * 2))
        val o = vCount * RenderConstants.FLOATS_PER_VERTEX
        verts[o] = px; verts[o + 1] = py; verts[o + 2] = pz
        verts[o + 3] = nx; verts[o + 4] = ny; verts[o + 5] = nz
        verts[o + 6] = u; verts[o + 7] = v
        verts[o + 8] = r; verts[o + 9] = g; verts[o + 10] = b; verts[o + 11] = a
        return vCount++
    }

    fun triangle(a: Int, b: Int, c: Int) {
        if (iCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[iCount++] = a; idx[iCount++] = b; idx[iCount++] = c
    }

    fun isEmpty(): Boolean = iCount == 0
}

/** Malla en GPU (VAO + VBO + EBO). */
class Mesh(data: MeshData, private val dynamic: Boolean = false) {
    private var vao = 0
    private var vbo = 0
    private var ebo = 0
    var indexCount = 0
        private set

    init { upload(data) }

    private fun upload(d: MeshData) {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glGenBuffers(1, ids, 0); ebo = ids[0]
        GLES30.glBindVertexArray(vao)

        val vb = ByteBuffer.allocateDirect(d.vCount * RenderConstants.STRIDE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(d.verts, 0, d.vCount * RenderConstants.FLOATS_PER_VERTEX).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, d.vCount * RenderConstants.STRIDE_BYTES, vb, if (dynamic) GLES30.GL_DYNAMIC_DRAW else GLES30.GL_STATIC_DRAW)

        val ib = ByteBuffer.allocateDirect(d.iCount * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(d.idx, 0, d.iCount).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, d.iCount * 4, ib, GLES30.GL_STATIC_DRAW)

        val s = RenderConstants.STRIDE_BYTES
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, s, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, s, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, s, 24)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, s, 32)
        GLES30.glBindVertexArray(0)
        indexCount = d.iCount
    }

    fun draw() {
        if (indexCount == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        val ids = IntArray(1)
        if (vao != 0) { ids[0] = vao; GLES30.glDeleteVertexArrays(1, ids, 0); vao = 0 }
        if (vbo != 0) { ids[0] = vbo; GLES30.glDeleteBuffers(1, ids, 0); vbo = 0 }
        if (ebo != 0) { ids[0] = ebo; GLES30.glDeleteBuffers(1, ids, 0); ebo = 0 }
        indexCount = 0
    }
}
