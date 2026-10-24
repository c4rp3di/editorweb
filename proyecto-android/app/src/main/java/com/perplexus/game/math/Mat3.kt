package com.perplexus.game.math

/** Matriz 3×3 en orden de fila mayor: m[fila*3 + col]. */
class Mat3(@JvmField val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) {

    init { require(m.size == 9) { "Mat3 requiere 9 elementos" } }

    operator fun get(r: Int, c: Int): Float = m[r * 3 + c]
    operator fun set(r: Int, c: Int, v: Float) { m[r * 3 + c] = v }

    fun setIdentity(): Mat3 {
        for (i in 0 until 9) m[i] = 0f
        m[0] = 1f; m[4] = 1f; m[8] = 1f
        return this
    }

    fun setDiagonal(d: Float): Mat3 { setIdentity(); m[0] = d; m[4] = d; m[8] = d; return this }

    fun set(o: Mat3): Mat3 { System.arraycopy(o.m, 0, m, 0, 9); return this }
    fun copy(): Mat3 = Mat3(m.copyOf())

    fun setFromQuat(q: Quat): Mat3 { q.toMat3(m); return this }

    fun mulVec(v: Vec3): Vec3 = mulVecInto(v, Vec3())

    fun mulVecInto(v: Vec3, out: Vec3): Vec3 {
        val rx = m[0] * v.x + m[1] * v.y + m[2] * v.z
        val ry = m[3] * v.x + m[4] * v.y + m[5] * v.z
        val rz = m[6] * v.x + m[7] * v.y + m[8] * v.z
        out.x = rx; out.y = ry; out.z = rz
        return out
    }

    /** Aplica la transpuesta: Mᵀ·v. */
    fun mulVecTransposeInto(v: Vec3, out: Vec3): Vec3 {
        val rx = m[0] * v.x + m[3] * v.y + m[6] * v.z
        val ry = m[1] * v.x + m[4] * v.y + m[7] * v.z
        val rz = m[2] * v.x + m[5] * v.y + m[8] * v.z
        out.x = rx; out.y = ry; out.z = rz
        return out
    }

    operator fun times(o: Mat3): Mat3 {
        val r = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        }
        return Mat3(r)
    }

    fun transposed(): Mat3 = Mat3(floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    fun determinant(): Float =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) +
        m[2] * (m[3] * m[7] - m[4] * m[6])

    /** Inversa; devuelve null si es singular. */
    fun inverse(): Mat3? {
        val det = determinant()
        if (kotlin.math.abs(det) < 1e-18f) return null
        val id = 1f / det
        val r = FloatArray(9)
        r[0] = (m[4] * m[8] - m[5] * m[7]) * id
        r[1] = (m[2] * m[7] - m[1] * m[8]) * id
        r[2] = (m[1] * m[5] - m[2] * m[4]) * id
        r[3] = (m[5] * m[6] - m[3] * m[8]) * id
        r[4] = (m[0] * m[8] - m[2] * m[6]) * id
        r[5] = (m[2] * m[3] - m[0] * m[5]) * id
        r[6] = (m[3] * m[7] - m[4] * m[6]) * id
        r[7] = (m[1] * m[6] - m[0] * m[7]) * id
        r[8] = (m[0] * m[4] - m[1] * m[3]) * id
        return Mat3(r)
    }

    companion object {
        @JvmStatic fun identity(): Mat3 = Mat3()
        @JvmStatic fun diagonal(d: Float): Mat3 = Mat3().setDiagonal(d)
    }
}
