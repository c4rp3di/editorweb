package com.perplexus.game.render

import com.perplexus.game.math.Vec3

/** Iluminación estática: luz principal (con sombras), relleno y ambiente. Direcciones = hacia donde viaja la luz. */
class Light {
    @JvmField val direction = Vec3(0.45f, -0.75f, -0.50f).also { it.normalize() }   // desde arriba-izquierda-delante
    @JvmField val color = floatArrayOf(1.0f, 0.97f, 0.92f)
    @JvmField val fillDirection = Vec3(-0.55f, 0.65f, -0.35f).also { it.normalize() } // desde abajo-derecha
    @JvmField val fillColor = floatArrayOf(0.22f, 0.26f, 0.34f)
    @JvmField val ambient = floatArrayOf(0.20f, 0.24f, 0.34f)                          // azul tenue
}

/** Parámetros de material de render. */
class RenderMaterial(
    @JvmField val tint: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    @JvmField val specular: Float = 0.5f,
    @JvmField val shininess: Float = 32f,
    @JvmField val emissive: FloatArray = floatArrayOf(0f, 0f, 0f),
    @JvmField val rim: Float = 0f,
    @JvmField val alpha: Float = 1f
) {
    companion object {
        @JvmField val BALL = RenderMaterial(specular = 1.2f, shininess = 64f)
        @JvmField val TRACK_SOLID = RenderMaterial(specular = 0.5f, shininess = 32f)
        @JvmField val TRACK_GLASS = RenderMaterial(specular = 0.6f, shininess = 32f, rim = 0.8f)
    }
}
