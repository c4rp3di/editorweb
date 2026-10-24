package com.perplexus.game.render

import kotlin.math.cos
import kotlin.math.sin

/** Generador de esferas UV. */
object SphereMesh {
    private const val PI = 3.14159265f

    /** Añade una esfera a [d]. Si [inward] las normales apuntan al centro. */
    fun add(
        d: MeshData, cx: Float, cy: Float, cz: Float, radius: Float, segW: Int, segH: Int,
        color: FloatArray, inward: Boolean = false
    ) {
        val base = d.vertexCount
        for (j in 0..segH) {
            val v = j.toFloat() / segH
            val phi = v * PI
            val y = cos(phi); val rr = sin(phi)
            for (i in 0..segW) {
                val u = i.toFloat() / segW
                val th = u * 2f * PI
                val x = rr * cos(th); val z = rr * sin(th)
                val s = if (inward) -1f else 1f
                d.vertex(cx + x * radius, cy + y * radius, cz + z * radius, x * s, y * s, z * s, u, v, color[0], color[1], color[2], color[3])
            }
        }
        val row = segW + 1
        for (j in 0 until segH) for (i in 0 until segW) {
            val a = base + j * row + i
            val b = a + row
            if (inward) { d.triangle(a, a + 1, b); d.triangle(a + 1, b + 1, b) }
            else { d.triangle(a, b, a + 1); d.triangle(a + 1, b, b + 1) }
        }
    }

    fun build(radius: Float, segW: Int, segH: Int, color: FloatArray = floatArrayOf(1f, 1f, 1f, 1f), inward: Boolean = false): MeshData {
        val d = MeshData()
        add(d, 0f, 0f, 0f, radius, segW, segH, color, inward)
        return d
    }
}

/** Bola: esfera unitaria 32×16 con UV esféricas (se escala con la matriz de modelo). */
object BallMesh {
    fun build(): MeshData = SphereMesh.build(1f, 32, 16)
}

/** Esfera exterior transparente de radio R (64×32 = 4096 triángulos). */
object ShellMesh {
    fun build(radius: Float): MeshData = SphereMesh.build(radius, 64, 32, floatArrayOf(0.5f, 0.6f, 0.9f, 1f), false)
}
