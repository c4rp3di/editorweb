package com.perplexus.game.render

import com.perplexus.game.math.MathUtils
import kotlin.math.abs
import kotlin.math.sin

/**
 * Textura procedural de mármol (blanco hueso con vetas grises y toques dorados).
 * Se puede pre-generar en un hilo de fondo; get() espera si aún no está lista.
 */
object ProceduralMarble {
    private var pixels: IntArray? = null
    private var size = 0
    private val lock = Object()
    private var started = false

    fun pregenerate(n: Int = RenderConstants.MARBLE_SIZE) {
        synchronized(lock) { if (started) return; started = true }
        Thread({
            val px = generate(n)
            synchronized(lock) { pixels = px; size = n; lock.notifyAll() }
        }, "MarbleGen").start()
    }

    fun get(n: Int = RenderConstants.MARBLE_SIZE): Pair<IntArray, Int> {
        pregenerate(n)
        synchronized(lock) {
            while (pixels == null) lock.wait()
            return Pair(pixels!!, size)
        }
    }

    fun generate(n: Int): IntArray {
        val out = IntArray(n * n)
        for (y in 0 until n) {
            val v = y.toFloat() / n
            for (x in 0 until n) {
                val u = x.toFloat() / n
                // Tres escalas de ruido de valor
                val n1 = ProceduralNoise.fbm(u * 6f, v * 3f, 11, 3)
                val n2 = ProceduralNoise.fbm(u * 14f + 5f, v * 7f, 23, 3)
                val warp = (n1 - 0.5f) * 5f + (n2 - 0.5f) * 1.2f
                val s = abs(sin((u * 9f + v * 4f + warp) * 3.14159f))
                val vein = 1f - MathUtils.smoothstep(0f, 0.16f, s)       // 1 sobre la veta
                val fine = 1f - MathUtils.smoothstep(0f, 0.05f, abs(sin((u * 23f - v * 11f + warp * 2f) * 3.14159f)))
                var r = 0.93f; var g = 0.91f; var b = 0.86f
                val cloud = ProceduralNoise.fbm(u * 10f, v * 5f, 37, 3)
                r -= 0.06f * cloud; g -= 0.06f * cloud; b -= 0.05f * cloud
                // Veta gris
                val k = vein * 0.75f + fine * 0.25f
                r = MathUtils.lerp(r, 0.42f, k); g = MathUtils.lerp(g, 0.45f, k); b = MathUtils.lerp(b, 0.50f, k)
                // Toque dorado en algunas vetas
                val gold = MathUtils.smoothstep(0.62f, 0.8f, ProceduralNoise.fbm(u * 4f, v * 2f, 53, 2)) * vein
                r = MathUtils.lerp(r, 0.85f, gold * 0.7f); g = MathUtils.lerp(g, 0.66f, gold * 0.7f); b = MathUtils.lerp(b, 0.28f, gold * 0.7f)
                val ri = (MathUtils.clamp(r, 0f, 1f) * 255f).toInt()
                val gi = (MathUtils.clamp(g, 0f, 1f) * 255f).toInt()
                val bi = (MathUtils.clamp(b, 0f, 1f) * 255f).toInt()
                out[y * n + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        return out
    }
}
