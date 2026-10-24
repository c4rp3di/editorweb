package com.perplexus.game.physics

/**
 * Propiedades de superficie.
 * restitution 0 = pegajoso, 1 = elástico. rolling = coeficiente de fricción de rodadura.
 */
class Material(
    @JvmField val name: String,
    @JvmField val restitution: Float,
    @JvmField val staticFriction: Float,
    @JvmField val dynamicFriction: Float,
    @JvmField val rolling: Float,
    @JvmField val density: Float = 1000f
) {
    companion object {
        @JvmField val BALL_STEEL = Material("ball", 0.65f, 0.6f, 0.4f, 0.005f, 7800f)
        @JvmField val METAL = Material("metal", 0.7f, 0.5f, 0.4f, 0.005f, 7800f)
        @JvmField val WOOD = Material("wood", 0.5f, 0.75f, 0.6f, 0.01f, 700f)
        @JvmField val RUBBER = Material("rubber", 0.3f, 1.0f, 0.9f, 0.02f, 1100f)
        @JvmField val ICE = Material("ice", 0.4f, 0.06f, 0.05f, 0.001f, 920f)

        @JvmStatic fun byName(name: String): Material = when (name.lowercase()) {
            "metal" -> METAL
            "wood" -> WOOD
            "rubber" -> RUBBER
            "ice" -> ICE
            "ball", "steel" -> BALL_STEEL
            else -> METAL
        }

        @JvmStatic fun isKnown(name: String): Boolean =
            when (name.lowercase()) { "metal", "wood", "rubber", "ice", "ball", "steel" -> true else -> false }
    }
}
