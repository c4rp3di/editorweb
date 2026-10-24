package com.perplexus.game.math

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cuaternión unitario (w, x, y, z). Convención: rotación activa, R(q)·v = q ⊗ v ⊗ q*.
 * Integración: q̇ = ½ q ⊗ (0, ω) con ω expresada en el sistema del cuerpo.
 */
class Quat(@JvmField var w: Float = 1f, @JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {

    fun set(nw: Float, nx: Float, ny: Float, nz: Float): Quat { w = nw; x = nx; y = ny; z = nz; return this }
    fun set(o: Quat): Quat { w = o.w; x = o.x; y = o.y; z = o.z; return this }
    fun setIdentity(): Quat { w = 1f; x = 0f; y = 0f; z = 0f; return this }
    fun copy(): Quat = Quat(w, x, y, z)

    fun lengthSq(): Float = w * w + x * x + y * y + z * z

    fun normalize(): Quat {
        val l2 = lengthSq()
        if (l2 < 1e-20f) return setIdentity()
        val inv = 1f / sqrt(l2)
        w *= inv; x *= inv; y *= inv; z *= inv
        return this
    }

    fun conjugate(): Quat = Quat(w, -x, -y, -z)
    fun conjugateInPlace(): Quat { x = -x; y = -y; z = -z; return this }

    /** this ⊗ o (nuevo objeto). */
    operator fun times(o: Quat): Quat = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w
    )

    /** this = this ⊗ o (in place). */
    fun mulInPlace(o: Quat): Quat {
        val nw = w * o.w - x * o.x - y * o.y - z * o.z
        val nx = w * o.x + x * o.w + y * o.z - z * o.y
        val ny = w * o.y - x * o.z + y * o.w + z * o.x
        val nz = w * o.z + x * o.y - y * o.x + z * o.w
        w = nw; x = nx; y = ny; z = nz
        return this
    }

    /** Rota v y escribe en out. */
    fun rotateInto(v: Vec3, out: Vec3): Vec3 {
        // t = 2 * (q.xyz × v); v' = v + w*t + q.xyz × t
        val tx = 2f * (y * v.z - z * v.y)
        val ty = 2f * (z * v.x - x * v.z)
        val tz = 2f * (x * v.y - y * v.x)
        val rx = v.x + w * tx + (y * tz - z * ty)
        val ry = v.y + w * ty + (z * tx - x * tz)
        val rz = v.z + w * tz + (x * ty - y * tx)
        out.x = rx; out.y = ry; out.z = rz
        return out
    }
    fun rotate(v: Vec3): Vec3 = rotateInto(v, Vec3())

    /** Rota por el inverso: R(q)ᵀ·v. */
    fun inverseRotateInto(v: Vec3, out: Vec3): Vec3 {
        val tx = 2f * (-y * v.z + z * v.y)
        val ty = 2f * (-z * v.x + x * v.z)
        val tz = 2f * (-x * v.y + y * v.x)
        val rx = v.x + w * tx + (-y * tz + z * ty)
        val ry = v.y + w * ty + (-z * tx + x * tz)
        val rz = v.z + w * tz + (-x * ty + y * tx)
        out.x = rx; out.y = ry; out.z = rz
        return out
    }

    /** Integra q += ½ q ⊗ (0, ω) dt, con ω en el sistema del cuerpo. Renormaliza. */
    fun integrate(wx: Float, wy: Float, wz: Float, dt: Float): Quat {
        val h = 0.5f * dt
        val dw = (-x * wx - y * wy - z * wz) * h
        val dx = (w * wx + y * wz - z * wy) * h
        val dy = (w * wy - x * wz + z * wx) * h
        val dz = (w * wz + x * wy - y * wx) * h
        w += dw; x += dx; y += dy; z += dz
        return normalize()
    }
    fun integrate(omega: Vec3, dt: Float): Quat = integrate(omega.x, omega.y, omega.z, dt)

    /** Escribe la matriz 3×3 (fila mayor) de R(q). */
    fun toMat3(m: FloatArray) {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        m[0] = 1f - 2f * (yy + zz); m[1] = 2f * (xy - wz);       m[2] = 2f * (xz + wy)
        m[3] = 2f * (xy + wz);       m[4] = 1f - 2f * (xx + zz); m[5] = 2f * (yz - wx)
        m[6] = 2f * (xz - wy);       m[7] = 2f * (yz + wx);       m[8] = 1f - 2f * (xx + yy)
    }

    fun dot(o: Quat): Float = w * o.w + x * o.x + y * o.y + z * o.z

    /** Ángulo (rad) de rotación relativa respecto a otro cuaternión. */
    fun angleTo(o: Quat): Float {
        val d = kotlin.math.abs(dot(o)).coerceIn(0f, 1f)
        return 2f * acos(d)
    }

    override fun toString(): String = "(w=%.4f, x=%.4f, y=%.4f, z=%.4f)".format(w, x, y, z)

    companion object {
        @JvmStatic fun fromAxisAngle(ax: Float, ay: Float, az: Float, angle: Float): Quat {
            val l = sqrt(ax * ax + ay * ay + az * az)
            if (l < 1e-12f) return Quat()
            val s = sin(angle * 0.5f) / l
            return Quat(cos(angle * 0.5f), ax * s, ay * s, az * s)
        }

        /** Rotación mínima que lleva la dirección a sobre la dirección b. */
        @JvmStatic fun fromTwoVectors(a: Vec3, b: Vec3): Quat {
            val an = a.normalized(); val bn = b.normalized()
            val d = an.dot(bn)
            if (d > 0.999999f) return Quat()
            if (d < -0.999999f) {
                // 180°: eje perpendicular cualquiera
                var axis = Vec3(1f, 0f, 0f).cross(an)
                if (axis.lengthSq() < 1e-6f) axis = Vec3(0f, 1f, 0f).cross(an)
                axis.normalize()
                return Quat(0f, axis.x, axis.y, axis.z)
            }
            val c = an.cross(bn)
            return Quat(1f + d, c.x, c.y, c.z).normalize()
        }

        @JvmStatic fun slerp(a: Quat, b: Quat, t: Float): Quat {
            var d = a.dot(b)
            var bw = b.w; var bx = b.x; var by = b.y; var bz = b.z
            if (d < 0f) { d = -d; bw = -bw; bx = -bx; by = -by; bz = -bz }
            if (d > 0.9995f) {
                return Quat(
                    a.w + (bw - a.w) * t, a.x + (bx - a.x) * t,
                    a.y + (by - a.y) * t, a.z + (bz - a.z) * t
                ).normalize()
            }
            val th = acos(d)
            val sinTh = sin(th)
            val wa = sin((1f - t) * th) / sinTh
            val wb = sin(t * th) / sinTh
            return Quat(wa * a.w + wb * bw, wa * a.x + wb * bx, wa * a.y + wb * by, wa * a.z + wb * bz).normalize()
        }
    }
}
