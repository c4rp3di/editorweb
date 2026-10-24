package com.perplexus.game.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Operaciones sobre matrices 4×4 almacenadas como FloatArray(16) en orden de columna mayor
 * (formato nativo de OpenGL): m[col*4 + fila].
 */
object Mat4 {

    @JvmStatic fun identity(): FloatArray = FloatArray(16).also { setIdentity(it) }

    @JvmStatic fun setIdentity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    /** out = a · b. out no puede ser a ni b. */
    @JvmStatic fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                out[c * 4 + r] =
                    a[0 * 4 + r] * b[c * 4 + 0] +
                    a[1 * 4 + r] * b[c * 4 + 1] +
                    a[2 * 4 + r] * b[c * 4 + 2] +
                    a[3 * 4 + r] * b[c * 4 + 3]
            }
        }
    }

    @JvmStatic fun perspective(m: FloatArray, fovYRad: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovYRad * 0.5f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = (2f * far * near) / (near - far)
    }

    @JvmStatic fun orthographic(m: FloatArray, l: Float, r: Float, b: Float, t: Float, n: Float, f: Float) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 2f / (r - l)
        m[5] = 2f / (t - b)
        m[10] = -2f / (f - n)
        m[12] = -(r + l) / (r - l)
        m[13] = -(t + b) / (t - b)
        m[14] = -(f + n) / (f - n)
        m[15] = 1f
    }

    @JvmStatic fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float) {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl < 1e-12f) { setIdentity(m); return }
        fx /= fl; fy /= fl; fz /= fl
        // s = f × up
        var sx = fy * uz - fz * uy
        var sy = fz * ux - fx * uz
        var sz = fx * uy - fy * ux
        var sl = sqrt(sx * sx + sy * sy + sz * sz)
        if (sl < 1e-9f) {
            // up paralelo a f: elegir otro up
            sx = fy * 0f - fz * 1f; sy = fz * 0f - fx * 0f; sz = fx * 1f - fy * 0f
            sl = sqrt(sx * sx + sy * sy + sz * sz)
            if (sl < 1e-9f) { sx = 1f; sy = 0f; sz = 0f; sl = 1f }
        }
        sx /= sl; sy /= sl; sz /= sl
        // u = s × f
        val tx = sy * fz - sz * fy
        val ty = sz * fx - sx * fz
        val tz = sx * fy - sy * fx
        m[0] = sx; m[1] = tx; m[2] = -fx; m[3] = 0f
        m[4] = sy; m[5] = ty; m[6] = -fy; m[7] = 0f
        m[8] = sz; m[9] = tz; m[10] = -fz; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(tx * ex + ty * ey + tz * ez)
        m[14] = (fx * ex + fy * ey + fz * ez)
        m[15] = 1f
    }

    @JvmStatic fun setTranslation(m: FloatArray, x: Float, y: Float, z: Float) {
        setIdentity(m); m[12] = x; m[13] = y; m[14] = z
    }

    @JvmStatic fun setScale(m: FloatArray, sx: Float, sy: Float, sz: Float) {
        setIdentity(m); m[0] = sx; m[5] = sy; m[10] = sz
    }

    /** Matriz de rotación a partir de un cuaternión (con traslación opcional). */
    @JvmStatic fun fromQuat(m: FloatArray, q: Quat, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f) {
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        m[0] = 1f - 2f * (yy + zz); m[1] = 2f * (xy + wz);       m[2] = 2f * (xz - wy);       m[3] = 0f
        m[4] = 2f * (xy - wz);       m[5] = 1f - 2f * (xx + zz); m[6] = 2f * (yz + wx);       m[7] = 0f
        m[8] = 2f * (xz + wy);       m[9] = 2f * (yz - wx);       m[10] = 1f - 2f * (xx + yy); m[11] = 0f
        m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
    }

    /** Matriz de rotación 4×4 a partir de una Mat3 de fila mayor (más traslación). */
    @JvmStatic fun fromMat3(m: FloatArray, r: FloatArray, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f) {
        m[0] = r[0]; m[1] = r[3]; m[2] = r[6]; m[3] = 0f
        m[4] = r[1]; m[5] = r[4]; m[6] = r[7]; m[7] = 0f
        m[8] = r[2]; m[9] = r[5]; m[10] = r[8]; m[11] = 0f
        m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
    }

    /** Traslación · rotación · escala uniforme. */
    @JvmStatic fun trs(m: FloatArray, q: Quat, tx: Float, ty: Float, tz: Float, s: Float) {
        fromQuat(m, q, tx, ty, tz)
        for (i in 0 until 3) { m[i] *= s; m[4 + i] *= s; m[8 + i] *= s }
    }

    @JvmStatic fun transpose(out: FloatArray, a: FloatArray) {
        for (c in 0 until 4) for (r in 0 until 4) out[c * 4 + r] = a[r * 4 + c]
    }

    /** Inversa general. Devuelve false si es singular. out puede ser distinto de a. */
    @JvmStatic fun invert(out: FloatArray, a: FloatArray): Boolean {
        val inv = FloatArray(16)
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (abs(det) < 1e-20f) return false
        val id = 1f / det
        for (i in 0 until 16) out[i] = inv[i] * id
        return true
    }

    /** Matriz normal (inversa-transpuesta de la 3×3 superior) en columna mayor 3×3. */
    @JvmStatic fun normalMatrix(out3: FloatArray, model: FloatArray) {
        // Para transformaciones con escala uniforme basta la 3×3 normalizada; calculamos la general.
        val a = model[0]; val b = model[4]; val c = model[8]
        val d = model[1]; val e = model[5]; val f = model[9]
        val g = model[2]; val h = model[6]; val i = model[10]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-20f) {
            out3[0] = 1f; out3[1] = 0f; out3[2] = 0f
            out3[3] = 0f; out3[4] = 1f; out3[5] = 0f
            out3[6] = 0f; out3[7] = 0f; out3[8] = 1f
            return
        }
        val id = 1f / det
        // inversa (fila mayor) transpuesta = cofactores / det
        val c00 = (e * i - f * h) * id
        val c01 = (f * g - d * i) * id
        val c02 = (d * h - e * g) * id
        val c10 = (c * h - b * i) * id
        val c11 = (a * i - c * g) * id
        val c12 = (b * g - a * h) * id
        val c20 = (b * f - c * e) * id
        val c21 = (c * d - a * f) * id
        val c22 = (a * e - b * d) * id
        // Escribimos en columna mayor: out[col*3 + fila] = cof[fila][col]
        out3[0] = c00; out3[1] = c10; out3[2] = c20
        out3[3] = c01; out3[4] = c11; out3[5] = c21
        out3[6] = c02; out3[7] = c12; out3[8] = c22
    }

    /** Transforma un punto (w=1). */
    @JvmStatic fun transformPoint(m: FloatArray, p: Vec3, out: Vec3): Vec3 {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        out.x = x; out.y = y; out.z = z
        return out
    }

    @JvmStatic fun rotationAxisAngle(m: FloatArray, ax: Float, ay: Float, az: Float, angle: Float) {
        val l = sqrt(ax * ax + ay * ay + az * az)
        if (l < 1e-12f) { setIdentity(m); return }
        val x = ax / l; val y = ay / l; val z = az / l
        val c = cos(angle); val s = sin(angle); val t = 1f - c
        m[0] = t * x * x + c;     m[1] = t * x * y + s * z; m[2] = t * x * z - s * y; m[3] = 0f
        m[4] = t * x * y - s * z; m[5] = t * y * y + c;     m[6] = t * y * z + s * x; m[7] = 0f
        m[8] = t * x * z + s * y; m[9] = t * y * z - s * x; m[10] = t * z * z + c;     m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }
}
