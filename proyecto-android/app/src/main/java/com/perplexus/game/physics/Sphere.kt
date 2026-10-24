package com.perplexus.game.physics

import com.perplexus.game.math.Vec3

/** Esfera maciza (obstáculo/bumper). */
class Sphere(
    @JvmField val center: Vec3, @JvmField val radius: Float, material: Material = Material.METAL
) : Shape(material) {
    override val kind: ShapeKind get() = ShapeKind.SPHERE
    init { computeAabb() }
    override fun computeAabb() {
        aabb.set(center.x - radius, center.y - radius, center.z - radius, center.x + radius, center.y + radius, center.z + radius)
    }
}
