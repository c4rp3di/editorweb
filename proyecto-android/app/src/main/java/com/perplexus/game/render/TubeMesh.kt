package com.perplexus.game.render

import com.perplexus.game.math.Vec3
import com.perplexus.game.physics.Tube
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Generación de tubos huecos con ranura, cilindros macizos y cápsulas. */
object TubeMesh {
    private const val TWO_PI = 6.2831853f

    /**
     * Tubo hueco. La ranura (si open > 0) se recorta en cada anillo usando la misma dirección que
     * usa la física (Tube.slotDirection), de modo que lo visible coincide con la colisión.
     */
    fun addTube(d: MeshData, t: Tube, color: FloatArray, radial: Int = RenderConstants.TUBE_RADIAL_SEGMENTS) {
        val len = t.length
        if (len < 1e-5f) return
        val rings = maxOf(1, ceil(len / RenderConstants.TUBE_MAX_AXIAL_LEN).toInt())
        var open = t.openHalfAngle
        if (open > 3.0f) open = 3.0f
        val arcStart = open
        val arcLen = TWO_PI - 2f * open
        val ax = t.axis
        val sd = Vec3()
        val base = d.vertexCount
        for (k in 0..rings) {
            val s = len * k / rings
            val cx = t.a.x + ax.x * s; val cy = t.a.y + ax.y * s; val cz = t.a.z + ax.z * s
            t.slotDirection(s, sd)
            // e2 = axis × sd
            val e2x = ax.y * sd.z - ax.z * sd.y
            val e2y = ax.z * sd.x - ax.x * sd.z
            val e2z = ax.x * sd.y - ax.y * sd.x
            for (j in 0..radial) {
                val phi = arcStart + arcLen * j / radial
                val c = cos(phi); val sn = sin(phi)
                val dx = sd.x * c + e2x * sn; val dy = sd.y * c + e2y * sn; val dz = sd.z * c + e2z * sn
                d.vertex(cx + dx * t.radius, cy + dy * t.radius, cz + dz * t.radius, dx, dy, dz,
                    j.toFloat() / radial, k.toFloat() / rings, color[0], color[1], color[2], color[3])
            }
        }
        val row = radial + 1
        for (k in 0 until rings) for (j in 0 until radial) {
            val a = base + k * row + j
            val b = a + row
            d.triangle(a, b, a + 1); d.triangle(a + 1, b, b + 1)
        }
        if (t.capA) addDisc(d, t.a.x, t.a.y, t.a.z, ax.x, ax.y, ax.z, t.radius, color, radial)
        if (t.capB) addDisc(d, t.b.x, t.b.y, t.b.z, -ax.x, -ax.y, -ax.z, t.radius, color, radial)
    }

    /** Disco (tapa) con normal (nx,ny,nz). */
    fun addDisc(d: MeshData, cx: Float, cy: Float, cz: Float, nx: Float, ny: Float, nz: Float, radius: Float, color: FloatArray, segs: Int) {
        val ex = Vec3(); val ey = Vec3()
        basis(nx, ny, nz, ex, ey)
        val c = d.vertex(cx, cy, cz, nx, ny, nz, 0.5f, 0.5f, color[0], color[1], color[2], color[3])
        val first = d.vertexCount
        for (j in 0..segs) {
            val phi = TWO_PI * j / segs
            val co = cos(phi); val si = sin(phi)
            d.vertex(cx + (ex.x * co + ey.x * si) * radius, cy + (ex.y * co + ey.y * si) * radius, cz + (ex.z * co + ey.z * si) * radius,
                nx, ny, nz, 0.5f + 0.5f * co, 0.5f + 0.5f * si, color[0], color[1], color[2], color[3])
        }
        for (j in 0 until segs) d.triangle(c, first + j, first + j + 1)
    }

    /** Base ortonormal (ex, ey) perpendicular a n. */
    fun basis(nx: Float, ny: Float, nz: Float, ex: Vec3, ey: Vec3) {
        if (abs(nx) < 0.9f) ex.set(0f, -nz, ny) else ex.set(-nz, 0f, nx)
        ex.normalize()
        ey.set(ny * ex.z - nz * ex.y, nz * ex.x - nx * ex.z, nx * ex.y - ny * ex.x)
        ey.normalize()
    }

    /** Cilindro macizo con extremos esféricos (cápsula). */
    fun addCapsule(d: MeshData, a: Vec3, b: Vec3, radius: Float, color: FloatArray, radial: Int = 16) {
        val ax = b.minus(a)
        val len = ax.normalize()
        if (len > 1e-5f) {
            val ex = Vec3(); val ey = Vec3()
            basis(ax.x, ax.y, ax.z, ex, ey)
            val rings = maxOf(1, ceil(len / 0.15f).toInt())
            val base = d.vertexCount
            for (k in 0..rings) {
                val s = len * k / rings
                for (j in 0..radial) {
                    val phi = TWO_PI * j / radial
                    val co = cos(phi); val si = sin(phi)
                    val dx = ex.x * co + ey.x * si; val dy = ex.y * co + ey.y * si; val dz = ex.z * co + ey.z * si
                    d.vertex(a.x + ax.x * s + dx * radius, a.y + ax.y * s + dy * radius, a.z + ax.z * s + dz * radius,
                        dx, dy, dz, j.toFloat() / radial, k.toFloat() / rings, color[0], color[1], color[2], color[3])
                }
            }
            val row = radial + 1
            for (k in 0 until rings) for (j in 0 until radial) {
                val p = base + k * row + j; val q = p + row
                d.triangle(p, q, p + 1); d.triangle(p + 1, q, q + 1)
            }
        }
        SphereMesh.add(d, a.x, a.y, a.z, radius, radial, radial / 2, color)
        SphereMesh.add(d, b.x, b.y, b.z, radius, radial, radial / 2, color)
    }
}
