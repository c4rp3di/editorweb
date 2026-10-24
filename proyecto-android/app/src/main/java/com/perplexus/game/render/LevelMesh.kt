package com.perplexus.game.render

import com.perplexus.game.levels.Level
import com.perplexus.game.math.Mat3
import com.perplexus.game.physics.Box
import com.perplexus.game.physics.Capsule
import com.perplexus.game.physics.Material
import com.perplexus.game.physics.Sphere
import com.perplexus.game.physics.Triangle
import com.perplexus.game.physics.Tube
import com.perplexus.game.physics.TubeJoint

/** Convierte la geometría de colisión de un nivel en mallas: sólidos opacos y "cristal" translúcido. */
object LevelMesh {
    class Result(@JvmField val opaque: MeshData, @JvmField val glass: MeshData)

    fun colorOf(m: Material): FloatArray = when (m.name) {
        "wood" -> RenderConstants.COLOR_WOOD
        "rubber" -> RenderConstants.COLOR_RUBBER
        "ice" -> RenderConstants.COLOR_ICE
        else -> RenderConstants.COLOR_METAL
    }

    fun build(level: Level): Result {
        val opaque = MeshData()
        val glass = MeshData()
        for (s in level.shapes) {
            val c = colorOf(s.material)
            when (s) {
                is Tube -> TubeMesh.addTube(glass, s, c)
                is TubeJoint -> SphereMesh.add(glass, s.center.x, s.center.y, s.center.z, s.radius, 16, 8, c)
                is Capsule -> TubeMesh.addCapsule(opaque, s.a, s.b, s.radius, solid(c))
                is Sphere -> SphereMesh.add(opaque, s.center.x, s.center.y, s.center.z, s.radius, 20, 10, solid(c))
                is Triangle -> addTriangle(opaque, s, solid(c))
                is Box -> addBox(opaque, s, solid(c))
                else -> {}
            }
        }
        return Result(opaque, glass)
    }

    private fun solid(c: FloatArray): FloatArray = floatArrayOf(c[0], c[1], c[2], 1f)

    private fun addTriangle(d: MeshData, t: Triangle, c: FloatArray) {
        val n = t.normal
        val a = d.vertex(t.v0.x, t.v0.y, t.v0.z, n.x, n.y, n.z, 0f, 0f, c[0], c[1], c[2], c[3])
        val b = d.vertex(t.v1.x, t.v1.y, t.v1.z, n.x, n.y, n.z, 1f, 0f, c[0], c[1], c[2], c[3])
        val e = d.vertex(t.v2.x, t.v2.y, t.v2.z, n.x, n.y, n.z, 0f, 1f, c[0], c[1], c[2], c[3])
        d.triangle(a, b, e)
    }

    private fun addBox(d: MeshData, b: Box, c: FloatArray) {
        val m: FloatArray = b.rot.m
        val h = b.halfExtents
        // Seis caras: eje, signo
        for (axis in 0 until 3) for (sign in intArrayOf(-1, 1)) {
            val u = (axis + 1) % 3; val v = (axis + 2) % 3
            val hs = floatArrayOf(h.x, h.y, h.z)
            val n = FloatArray(3); n[axis] = sign.toFloat()
            val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
            val idx = IntArray(4)
            for (q in 0 until 4) {
                val l = FloatArray(3)
                l[axis] = sign * hs[axis]; l[u] = corners[q][0] * hs[u]; l[v] = corners[q][1] * hs[v]
                val wx = m[0] * l[0] + m[1] * l[1] + m[2] * l[2] + b.center.x
                val wy = m[3] * l[0] + m[4] * l[1] + m[5] * l[2] + b.center.y
                val wz = m[6] * l[0] + m[7] * l[1] + m[8] * l[2] + b.center.z
                val nx = m[0] * n[0] + m[1] * n[1] + m[2] * n[2]
                val ny = m[3] * n[0] + m[4] * n[1] + m[5] * n[2]
                val nz = m[6] * n[0] + m[7] * n[1] + m[8] * n[2]
                idx[q] = d.vertex(wx, wy, wz, nx, ny, nz, (corners[q][0] + 1f) / 2f, (corners[q][1] + 1f) / 2f, c[0], c[1], c[2], c[3])
            }
            d.triangle(idx[0], idx[1], idx[2]); d.triangle(idx[0], idx[2], idx[3])
        }
    }
}
