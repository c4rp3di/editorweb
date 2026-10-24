package com.perplexus.game.physics

import com.perplexus.game.math.Vec3

/** Triángulo (rampa/plano). Colisiona por ambas caras. */
class Triangle(
    @JvmField val v0: Vec3, @JvmField val v1: Vec3, @JvmField val v2: Vec3, material: Material = Material.METAL
) : Shape(material) {
    @JvmField val normal = Vec3()
    @JvmField val ab = Vec3()
    @JvmField val ac = Vec3()
    override val kind: ShapeKind get() = ShapeKind.TRIANGLE
    init { computeAabb() }
    override fun computeAabb() {
        ab.set(v1).sub(v0)
        ac.set(v2).sub(v0)
        ab.crossInto(ac, normal)
        normal.normalize()
        aabb.reset().expandPoint(v0).expandPoint(v1).expandPoint(v2)
    }
    fun area(): Float = 0.5f * ab.cross(ac).length()
}
