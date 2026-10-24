package com.perplexus.game.physics

import com.perplexus.game.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Detección de contacto exacta entre la bola (esfera) y cada tipo de primitiva.
 * Genera contactos con la normal apuntando de la superficie hacia la bola.
 * No es thread-safe (usa temporales); hay una instancia por PhysicsWorld.
 */
class Narrowphase {
    private val cp = Vec3()
    private val tmp = Vec3()
    private val slot = Vec3()
    private val lp = Vec3()

    // Propiedades de la bola, rellenadas por el mundo
    @JvmField var ball: Material = Material.BALL_STEEL

    fun collide(s: Shape, p: Vec3, r: Float, out: ContactBuffer): Int = when (s) {
        is Tube -> sphereTube(s, p, r, out)
        is TubeJoint -> sphereTubeJoint(s, p, r, out)
        is Capsule -> sphereCapsule(s, p, r, out)
        is Triangle -> sphereTriangle(s, p, r, out)
        is Sphere -> sphereSphere(s, p, r, out)
        is Box -> sphereBox(s, p, r, out)
        is Shell -> sphereShell(s, p, r, out)
        else -> 0
    }

    private fun emit(
        out: ContactBuffer, s: Shape, nx: Float, ny: Float, nz: Float,
        px: Float, py: Float, pz: Float, pen: Float, lethal: Boolean
    ): Int {
        val c = out.next() ?: return 0
        c.normal.set(nx, ny, nz)
        c.point.set(px, py, pz)
        c.penetration = pen
        val m = s.material
        // Combinación según el plan §7.7
        c.restitution = if (ball.restitution > m.restitution) ball.restitution else m.restitution
        c.staticFriction = sqrt(ball.staticFriction * m.staticFriction)
        c.dynamicFriction = sqrt(ball.dynamicFriction * m.dynamicFriction)
        c.rolling = 0.5f * (ball.rolling + m.rolling)
        c.lethal = lethal
        c.shapeId = s.id
        return 1
    }

    /** Punto más cercano del segmento A-B a P → out. Devuelve el parámetro t en [0,1]. */
    fun closestOnSegment(px: Float, py: Float, pz: Float, a: Vec3, b: Vec3, out: Vec3): Float {
        val abx = b.x - a.x; val aby = b.y - a.y; val abz = b.z - a.z
        val l2 = abx * abx + aby * aby + abz * abz
        var t = 0f
        if (l2 > 1e-12f) {
            t = ((px - a.x) * abx + (py - a.y) * aby + (pz - a.z) * abz) / l2
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
        }
        out.x = a.x + abx * t; out.y = a.y + aby * t; out.z = a.z + abz * t
        return t
    }

    // ---------------------------------------------------------------- Esfera vs Cápsula (maciza)
    fun sphereCapsule(s: Capsule, p: Vec3, r: Float, out: ContactBuffer): Int {
        closestOnSegment(p.x, p.y, p.z, s.a, s.b, cp)
        var dx = p.x - cp.x; var dy = p.y - cp.y; var dz = p.z - cp.z
        val d2 = dx * dx + dy * dy + dz * dz
        val rr = s.radius + r
        if (d2 >= rr * rr) return 0
        val d = sqrt(d2)
        if (d > 1e-6f) {
            dx /= d; dy /= d; dz /= d
        } else {
            // Caso d == 0: dirección perpendicular a (B-A) más cercana a un eje principal
            perpendicularToSegment(s.a, s.b)
            dx = tmp.x; dy = tmp.y; dz = tmp.z
        }
        return emit(out, s, dx, dy, dz, cp.x + dx * s.radius, cp.y + dy * s.radius, cp.z + dz * s.radius, rr - d, false)
    }

    private fun perpendicularToSegment(a: Vec3, b: Vec3) {
        var ux = b.x - a.x; var uy = b.y - a.y; var uz = b.z - a.z
        val l = sqrt(ux * ux + uy * uy + uz * uz)
        if (l < 1e-9f) { tmp.set(0f, 1f, 0f); return }
        ux /= l; uy /= l; uz /= l
        // Eje principal menos alineado con u
        val ax = abs(ux); val ay = abs(uy); val az = abs(uz)
        var ex = 0f; var ey = 0f; var ez = 0f
        if (ax <= ay && ax <= az) ex = 1f else if (ay <= az) ey = 1f else ez = 1f
        val d = ex * ux + ey * uy + ez * uz
        var rx = ex - ux * d; var ry = ey - uy * d; var rz = ez - uz * d
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        if (rl < 1e-9f) { tmp.set(0f, 1f, 0f); return }
        rx /= rl; ry /= rl; rz /= rl
        tmp.set(rx, ry, rz)
    }

    // ---------------------------------------------------------------- Esfera vs Esfera (maciza)
    fun sphereSphere(s: Sphere, p: Vec3, r: Float, out: ContactBuffer): Int {
        var dx = p.x - s.center.x; var dy = p.y - s.center.y; var dz = p.z - s.center.z
        val d2 = dx * dx + dy * dy + dz * dz
        val rr = s.radius + r
        if (d2 >= rr * rr) return 0
        val d = sqrt(d2)
        if (d > 1e-6f) { dx /= d; dy /= d; dz /= d } else { dx = 0f; dy = 1f; dz = 0f }
        return emit(out, s, dx, dy, dz, s.center.x + dx * s.radius, s.center.y + dy * s.radius, s.center.z + dz * s.radius, rr - d, false)
    }

    // ---------------------------------------------------------------- Esfera vs Triángulo (Ericson)
    /** Punto más cercano del triángulo (a,b,c) a P → out. */
    fun closestOnTriangle(p: Vec3, a: Vec3, b: Vec3, c: Vec3, out: Vec3) {
        val abx = b.x - a.x; val aby = b.y - a.y; val abz = b.z - a.z
        val acx = c.x - a.x; val acy = c.y - a.y; val acz = c.z - a.z
        val apx = p.x - a.x; val apy = p.y - a.y; val apz = p.z - a.z
        val d1 = abx * apx + aby * apy + abz * apz
        val d2 = acx * apx + acy * apy + acz * apz
        if (d1 <= 0f && d2 <= 0f) { out.set(a); return }
        val bpx = p.x - b.x; val bpy = p.y - b.y; val bpz = p.z - b.z
        val d3 = abx * bpx + aby * bpy + abz * bpz
        val d4 = acx * bpx + acy * bpy + acz * bpz
        if (d3 >= 0f && d4 <= d3) { out.set(b); return }
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
            val v = d1 / (d1 - d3)
            out.set(a.x + abx * v, a.y + aby * v, a.z + abz * v); return
        }
        val cpx = p.x - c.x; val cpy = p.y - c.y; val cpz = p.z - c.z
        val d5 = abx * cpx + aby * cpy + abz * cpz
        val d6 = acx * cpx + acy * cpy + acz * cpz
        if (d6 >= 0f && d5 <= d6) { out.set(c); return }
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
            val w = d2 / (d2 - d6)
            out.set(a.x + acx * w, a.y + acy * w, a.z + acz * w); return
        }
        val va = d3 * d6 - d5 * d4
        if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            out.set(b.x + (c.x - b.x) * w, b.y + (c.y - b.y) * w, b.z + (c.z - b.z) * w); return
        }
        val denom = 1f / (va + vb + vc)
        val v = vb * denom
        val w = vc * denom
        out.set(a.x + abx * v + acx * w, a.y + aby * v + acy * w, a.z + abz * v + acz * w)
    }

    fun sphereTriangle(s: Triangle, p: Vec3, r: Float, out: ContactBuffer): Int {
        closestOnTriangle(p, s.v0, s.v1, s.v2, cp)
        var dx = p.x - cp.x; var dy = p.y - cp.y; var dz = p.z - cp.z
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 >= r * r) return 0
        val d = sqrt(d2)
        if (d > 1e-6f) {
            dx /= d; dy /= d; dz /= d
        } else {
            dx = s.normal.x; dy = s.normal.y; dz = s.normal.z
        }
        return emit(out, s, dx, dy, dz, cp.x, cp.y, cp.z, r - d, false)
    }

    // ---------------------------------------------------------------- Esfera vs Caja orientada
    fun sphereBox(s: Box, p: Vec3, r: Float, out: ContactBuffer): Int {
        val m = s.rot.m
        val dx0 = p.x - s.center.x; val dy0 = p.y - s.center.y; val dz0 = p.z - s.center.z
        // lp = Rᵀ · (p - c)
        val lx = m[0] * dx0 + m[3] * dy0 + m[6] * dz0
        val ly = m[1] * dx0 + m[4] * dy0 + m[7] * dz0
        val lz = m[2] * dx0 + m[5] * dy0 + m[8] * dz0
        val hx = s.halfExtents.x; val hy = s.halfExtents.y; val hz = s.halfExtents.z
        val cx = if (lx < -hx) -hx else if (lx > hx) hx else lx
        val cy = if (ly < -hy) -hy else if (ly > hy) hy else ly
        val cz = if (lz < -hz) -hz else if (lz > hz) hz else lz
        val ex = lx - cx; val ey = ly - cy; val ez = lz - cz
        val d2 = ex * ex + ey * ey + ez * ez
        var nlx: Float; var nly: Float; var nlz: Float
        val pen: Float
        var cxl = cx; var cyl = cy; var czl = cz
        if (d2 > 1e-12f) {
            if (d2 >= r * r) return 0
            val d = sqrt(d2)
            nlx = ex / d; nly = ey / d; nlz = ez / d
            pen = r - d
        } else {
            // Centro dentro de la caja: salir por la cara más cercana
            val px = hx - abs(lx); val py = hy - abs(ly); val pz = hz - abs(lz)
            nlx = 0f; nly = 0f; nlz = 0f
            if (px <= py && px <= pz) {
                nlx = if (lx >= 0f) 1f else -1f; cxl = nlx * hx; cyl = ly; czl = lz; pen = px + r
            } else if (py <= pz) {
                nly = if (ly >= 0f) 1f else -1f; cxl = lx; cyl = nly * hy; czl = lz; pen = py + r
            } else {
                nlz = if (lz >= 0f) 1f else -1f; cxl = lx; cyl = ly; czl = nlz * hz; pen = pz + r
            }
        }
        // Normal y punto a mundo: v_w = R · v_l
        val nx = m[0] * nlx + m[1] * nly + m[2] * nlz
        val ny = m[3] * nlx + m[4] * nly + m[5] * nlz
        val nz = m[6] * nlx + m[7] * nly + m[8] * nlz
        val wx = m[0] * cxl + m[1] * cyl + m[2] * czl + s.center.x
        val wy = m[3] * cxl + m[4] * cyl + m[5] * czl + s.center.y
        val wz = m[6] * cxl + m[7] * cyl + m[8] * czl + s.center.z
        return emit(out, s, nx, ny, nz, wx, wy, wz, pen, false)
    }

    // ---------------------------------------------------------------- Esfera vs Tubo hueco
    fun sphereTube(s: Tube, p: Vec3, r: Float, out: ContactBuffer): Int {
        val ux = s.axis.x; val uy = s.axis.y; val uz = s.axis.z
        val ax = p.x - s.a.x; val ay = p.y - s.a.y; val az = p.z - s.a.z
        val sAxial = ax * ux + ay * uy + az * uz
        var n = 0

        // Tapas de los extremos: solo actúan si la bola está radialmente dentro del tubo
        if (s.capA || s.capB) {
            val crx = ax - ux * sAxial; val cry = ay - uy * sAxial; val crz = az - uz * sAxial
            if (crx * crx + cry * cry + crz * crz < s.radius * s.radius) {
                if (s.capA && sAxial < r && sAxial > -s.radius) {
                    n += emit(out, s, ux, uy, uz, s.a.x + crx, s.a.y + cry, s.a.z + crz, r - sAxial, false)
                }
                if (s.capB && sAxial > s.length - r && sAxial < s.length + s.radius) {
                    n += emit(out, s, -ux, -uy, -uz, s.a.x + ux * s.length + crx, s.a.y + uy * s.length + cry, s.a.z + uz * s.length + crz, sAxial - (s.length - r), false)
                }
            }
        }

        if (sAxial < 0f || sAxial > s.length) return n

        // Vector radial respecto al eje
        var rx = ax - ux * sAxial; var ry = ay - uy * sAxial; var rz = az - uz * sAxial
        val rd2 = rx * rx + ry * ry + rz * rz
        val limit = s.radius - r
        if (limit <= 0f) return n
        if (rd2 <= limit * limit) return n
        // Solo restringe si el centro de la bola está DENTRO del tubo (un tubo no atrae lo que está fuera)
        if (rd2 >= s.radius * s.radius) return n
        val rd = sqrt(rd2)
        rx /= rd; ry /= rd; rz /= rd

        // Ranura: si el ángulo radial cae dentro de la abertura efectiva, no hay pared
        val open = s.effectiveOpenAngle(r)
        if (open > 0f) {
            s.slotDirection(sAxial, slot)
            val cosT = rx * slot.x + ry * slot.y + rz * slot.z
            if (cosT > cos(open)) return n
        }
        val pen = rd + r - s.radius
        val px = s.a.x + ux * sAxial + rx * s.radius
        val py = s.a.y + uy * sAxial + ry * s.radius
        val pz = s.a.z + uz * sAxial + rz * s.radius
        return n + emit(out, s, -rx, -ry, -rz, px, py, pz, pen, false)
    }

    // ---------------------------------------------------------------- Esfera vs codo hueco
    fun sphereTubeJoint(s: TubeJoint, p: Vec3, r: Float, out: ContactBuffer): Int {
        var dx = p.x - s.center.x; var dy = p.y - s.center.y; var dz = p.z - s.center.z
        if (dx * s.dirIn.x + dy * s.dirIn.y + dz * s.dirIn.z <= 0f) return 0
        for (i in s.dirsOut.indices) {
            val o = s.dirsOut[i]
            if (dx * o.x + dy * o.y + dz * o.z >= 0f) return 0
        }
        val d2 = dx * dx + dy * dy + dz * dz
        val limit = s.radius - r
        if (limit <= 0f || d2 <= limit * limit || d2 >= s.radius * s.radius) return 0
        val d = sqrt(d2)
        dx /= d; dy /= d; dz /= d
        return emit(out, s, -dx, -dy, -dz, s.center.x + dx * s.radius, s.center.y + dy * s.radius, s.center.z + dz * s.radius, d + r - s.radius, false)
    }

    // ---------------------------------------------------------------- Esfera vs esfera exterior (interior)
    fun sphereShell(s: Shell, p: Vec3, r: Float, out: ContactBuffer): Int {
        var dx = p.x - s.center.x; var dy = p.y - s.center.y; var dz = p.z - s.center.z
        val d2 = dx * dx + dy * dy + dz * dz
        val limit = s.radius - r
        if (limit <= 0f || d2 <= limit * limit) return 0
        val d = sqrt(d2)
        if (d > 1e-6f) { dx /= d; dy /= d; dz /= d } else { dx = 0f; dy = 1f; dz = 0f }
        return emit(out, s, -dx, -dy, -dz, s.center.x + dx * s.radius, s.center.y + dy * s.radius, s.center.z + dz * s.radius, d + r - s.radius, true)
    }
}
