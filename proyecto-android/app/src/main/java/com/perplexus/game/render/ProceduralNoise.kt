package com.perplexus.game.render

import com.perplexus.game.math.MathUtils
import kotlin.math.floor

/** Ruido de valor 2D suave (determinista, sin asignaciones). */
object ProceduralNoise {
    @JvmStatic fun value2(x: Float, y: Float, seed: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val sx = fx * fx * (3f - 2f * fx); val sy = fy * fy * (3f - 2f * fy)
        val a = MathUtils.hash01(xi, yi, seed)
        val b = MathUtils.hash01(xi + 1, yi, seed)
        val c = MathUtils.hash01(xi, yi + 1, seed)
        val d = MathUtils.hash01(xi + 1, yi + 1, seed)
        val top = a + (b - a) * sx
        val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    /** Ruido fractal con [octaves] escalas. Devuelve [0,1]. */
    @JvmStatic fun fbm(x: Float, y: Float, seed: Int, octaves: Int): Float {
        var amp = 0.5f; var f = 1f; var sum = 0f; var norm = 0f
        for (o in 0 until octaves) {
            sum += amp * value2(x * f, y * f, seed + o * 101)
            norm += amp
            amp *= 0.5f; f *= 2f
        }
        return sum / norm
    }
}
