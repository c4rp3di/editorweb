package com.laberinto3d.juego.game.physics

import kotlin.math.sqrt

/** Resultado de un contacto esfera-caja. La normal apunta de la caja hacia la bola. */
class Contact {
    @JvmField var nx = 0f
    @JvmField var ny = 0f
    @JvmField var nz = 0f
    @JvmField var depth = 0f
}

/** Detección esfera vs. AABB por punto más cercano, con caso especial de centro dentro de la caja. */
object Collision {

    /**
     * Comprueba si la esfera (px,py,pz,r) penetra en [box]. Si es así rellena [out]
     * y devuelve true. Sin asignaciones de memoria.
     */
    fun sphereAabb(px: Float, py: Float, pz: Float, r: Float, box: Box, out: Contact): Boolean {
        val mn = box.min
        val mx = box.max
        // Rechazo rápido por AABB expandida
        if (px < mn.x - r || px > mx.x + r ||
            py < mn.y - r || py > mx.y + r ||
            pz < mn.z - r || pz > mx.z + r
        ) return false

        val cx = px.coerceIn(mn.x, mx.x)
        val cy = py.coerceIn(mn.y, mx.y)
        val cz = pz.coerceIn(mn.z, mx.z)
        val dx = px - cx
        val dy = py - cy
        val dz = pz - cz
        val distSq = dx * dx + dy * dy + dz * dz

        if (distSq > 1e-10f) {
            if (distSq >= r * r) return false
            val dist = sqrt(distSq)
            val inv = 1f / dist
            out.nx = dx * inv
            out.ny = dy * inv
            out.nz = dz * inv
            out.depth = r - dist
            return true
        }

        // Centro dentro de la caja: se empuja por la cara más cercana (nunca NaN).
        var best = px - mn.x; var nx = -1f; var ny = 0f; var nz = 0f
        var d = mx.x - px; if (d < best) { best = d; nx = 1f; ny = 0f; nz = 0f }
        d = py - mn.y; if (d < best) { best = d; nx = 0f; ny = -1f; nz = 0f }
        d = mx.y - py; if (d < best) { best = d; nx = 0f; ny = 1f; nz = 0f }
        d = pz - mn.z; if (d < best) { best = d; nx = 0f; ny = 0f; nz = -1f }
        d = mx.z - pz; if (d < best) { best = d; nx = 0f; ny = 0f; nz = 1f }
        out.nx = nx; out.ny = ny; out.nz = nz
        out.depth = best + r
        return true
    }
}
