package com.perplexus.game.render

object RenderConstants {
    /** Floats por vértice: pos(3) normal(3) uv(2) color(4). */
    const val FLOATS_PER_VERTEX = 12
    const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    const val SHADOW_SIZE = 1024
    const val MARBLE_SIZE = 1024

    const val CAMERA_BASE_DISTANCE = 3.2f      // en radios de esfera
    const val CAMERA_MIN_ZOOM = 0.55f
    const val CAMERA_MAX_ZOOM = 1.5f
    const val CAMERA_MIN_DISTANCE_R = 1.15f    // nunca dentro de la esfera (RF-14)
    const val CAMERA_DEFAULT_PITCH_DEG = 12f
    const val CAMERA_NEAR = 0.05f
    const val CAMERA_FAR = 40f
    const val FOV_MIN_DEG = 35f

    const val TUBE_RADIAL_SEGMENTS = 20
    const val TUBE_MAX_AXIAL_LEN = 0.12f

    const val MAX_PARTICLES = 256

    // Colores por material (RGBA)
    val COLOR_METAL = floatArrayOf(0.62f, 0.72f, 0.88f, 0.34f)
    val COLOR_WOOD = floatArrayOf(0.72f, 0.48f, 0.26f, 0.40f)
    val COLOR_RUBBER = floatArrayOf(0.85f, 0.25f, 0.28f, 0.40f)
    val COLOR_ICE = floatArrayOf(0.62f, 0.90f, 1.00f, 0.30f)
}
