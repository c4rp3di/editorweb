package com.perplexus.game.math

import kotlin.math.sqrt

/**
 * Vector 3D mutable. Los métodos "in place" (set/add/sub/scale/addScaled/normalize)
 * no generan basura y se usan en el bucle de física; los operadores (+, -, *)
 * crean objetos nuevos y se reservan para código frío.
 */
class Vec3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {

    fun set(nx: Float, ny: Float, nz: Float): Vec3 { x = nx; y = ny; z = nz; return this }
    fun set(o: Vec3): Vec3 { x = o.x; y = o.y; z = o.z; return this }
    fun setZero(): Vec3 { x = 0f; y = 0f; z = 0f; return this }
    fun copy(): Vec3 = Vec3(x, y, z)

    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float): Vec3 = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    fun add(o: Vec3): Vec3 { x += o.x; y += o.y; z += o.z; return this }
    fun sub(o: Vec3): Vec3 { x -= o.x; y -= o.y; z -= o.z; return this }
    fun scale(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }
    fun addScaled(o: Vec3, s: Float): Vec3 { x += o.x * s; y += o.y * s; z += o.z * s; return this }
    fun negate(): Vec3 { x = -x; y = -y; z = -z; return this }

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun dot(ox: Float, oy: Float, oz: Float): Float = x * ox + y * oy + z * oz
    fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    /** out = this × o  (sin allocation). out puede ser this u o. */
    fun crossInto(o: Vec3, out: Vec3): Vec3 {
        val cx = y * o.z - z * o.y
        val cy = z * o.x - x * o.z
        val cz = x * o.y - y * o.x
        out.x = cx; out.y = cy; out.z = cz
        return out
    }

    fun lengthSq(): Float = x * x + y * y + z * z
    fun length(): Float = sqrt(lengthSq())

    fun distanceTo(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    fun distanceSqTo(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return dx * dx + dy * dy + dz * dz
    }

    fun normalized(): Vec3 {
        val l = length()
        return if (l > 1e-12f) Vec3(x / l, y / l, z / l) else Vec3(0f, 0f, 0f)
    }

    /** Normaliza in place. Devuelve la longitud previa. Si es ~0 deja el vector a 0. */
    fun normalize(): Float {
        val l = length()
        if (l > 1e-12f) { val inv = 1f / l; x *= inv; y *= inv; z *= inv } else { x = 0f; y = 0f; z = 0f }
        return l
    }

    /** Limita la longitud a max. */
    fun clampLength(max: Float): Vec3 {
        val l2 = lengthSq()
        if (l2 > max * max) { val s = max / sqrt(l2); x *= s; y *= s; z *= s }
        return this
    }

    fun isFinite(): Boolean = MathUtils.isFinite(x) && MathUtils.isFinite(y) && MathUtils.isFinite(z)

    fun lerpTo(o: Vec3, t: Float): Vec3 {
        x += (o.x - x) * t; y += (o.y - y) * t; z += (o.z - z) * t
        return this
    }

    override fun toString(): String = "(%.4f, %.4f, %.4f)".format(x, y, z)

    override fun equals(other: Any?): Boolean =
        other is Vec3 && other.x == x && other.y == y && other.z == z
    override fun hashCode(): Int = (x.hashCode() * 31 + y.hashCode()) * 31 + z.hashCode()

    companion object {
        @JvmStatic fun lerp(a: Vec3, b: Vec3, t: Float): Vec3 =
            Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
        @JvmStatic fun dist(a: Vec3, b: Vec3): Float = a.distanceTo(b)
    }
}

operator fun Float.times(v: Vec3): Vec3 = Vec3(v.x * this, v.y * this, v.z * this)
