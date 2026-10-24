package com.perplexus.game.physics

import com.perplexus.game.math.Vec3

/** Varilla maciza: segmento A-B con radio. La bola rueda por fuera. */
class Capsule(
    @JvmField val a: Vec3, @JvmField val b: Vec3, @JvmField val radius: Float, material: Material = Material.METAL
) : Shape(material) {
    override val kind: ShapeKind get() = ShapeKind.CAPSULE
    init { computeAabb() }
    override fun computeAabb() {
        aabb.reset().expandPoint(a).expandPoint(b).inflate(radius)
    }
}
