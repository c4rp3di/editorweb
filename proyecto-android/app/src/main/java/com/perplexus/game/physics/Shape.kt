package com.perplexus.game.physics

import com.perplexus.game.math.AABB

enum class ShapeKind { CAPSULE, TRIANGLE, SPHERE, BOX, TUBE, TUBE_JOINT, SHELL }

/** Primitiva de colisión estática del mundo. */
abstract class Shape(@JvmField val material: Material) {
    @JvmField val aabb: AABB = AABB()
    @JvmField var id: Int = -1
    abstract val kind: ShapeKind
    /** Recalcula la AABB a partir de la geometría. */
    abstract fun computeAabb()
}
