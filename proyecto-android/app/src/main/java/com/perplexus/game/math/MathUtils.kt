package com.perplexus.game.math

import kotlin.math.abs
import kotlin.math.sqrt

/** Utilidades numéricas puras (sin Android). Todo en Float. */
object MathUtils {
    const val PI_F: Float = 3.14159265358979f
    const val TWO_PI_F: Float = 6.28318530717959f
    const val HALF_PI_F: Float = 1.5707963267949f
    const val DEG2RAD: Float = 0.017453292519943f
    const val RAD2DEG: Float = 57.29577951308232f
    const val EPS: Float = 1e-6f

    @JvmStatic fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
    @JvmStatic fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    @JvmStatic fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    @JvmStatic fun toRad(deg: Float): Float = deg * DEG2RAD
    @JvmStatic fun toDeg(rad: Float): Float = rad * RAD2DEG
    @JvmStatic fun sq(v: Float): Float = v * v
    @JvmStatic fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp((x - e0) / (e1 - e0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }
    @JvmStatic fun approxEq(a: Float, b: Float, eps: Float = 1e-4f): Boolean = abs(a - b) <= eps
    @JvmStatic fun isFinite(v: Float): Boolean = !(v.isNaN() || v.isInfinite())
    @JvmStatic fun safeSqrt(v: Float): Float = if (v <= 0f) 0f else sqrt(v)

    /** Hash entero determinista (para ruido procedural). */
    @JvmStatic fun hash2(x: Int, y: Int, seed: Int): Int {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return h
    }
    @JvmStatic fun hash01(x: Int, y: Int, seed: Int): Float =
        ((hash2(x, y, seed) ushr 8) and 0xFFFFFF).toFloat() / 16777215f
}
