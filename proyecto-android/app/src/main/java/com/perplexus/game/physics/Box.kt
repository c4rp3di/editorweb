package com.perplexus.game.physics

import com.perplexus.game.math.Mat3
import com.perplexus.game.math.Quat
import com.perplexus.game.math.Vec3
import kotlin.math.abs

/** Caja orientada (OBB) maciza. */
class Box(
    @JvmField val center: Vec3, @JvmField val halfExtents: Vec3, @JvmField val rotation: Quat = Quat(),
    material: Material = Material.METAL
) : Shape(material) {
    /** Matriz de rotación local→mundo (columnas = ejes de la caja). */
    @JvmField val rot = Mat3()
    override val kind: ShapeKind get() = ShapeKind.BOX
    init { computeAabb() }
    override fun computeAabb() {
        rot.setFromQuat(rotation)
        // Extensión de la AABB = Σ |R_ij|·h_j
        val m = rot.m
        val ex = abs(m[0]) * halfExtents.x + abs(m[1]) * halfExtents.y + abs(m[2]) * halfExtents.z
        val ey = abs(m[3]) * halfExtents.x + abs(m[4]) * halfExtents.y + abs(m[5]) * halfExtents.z
        val ez = abs(m[6]) * halfExtents.x + abs(m[7]) * halfExtents.y + abs(m[8]) * halfExtents.z
        aabb.set(center.x - ex, center.y - ey, center.z - ez, center.x + ex, center.y + ey, center.z + ez)
    }
}
