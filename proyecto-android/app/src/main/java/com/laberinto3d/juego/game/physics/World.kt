package com.laberinto3d.juego.game.physics

/**
 * Conjunto de cajas estáticas del nivel + datos de la meta.
 * La meta no es una caja con colisión: sólo un punto y un radio.
 */
class World(
    @JvmField val boxes: List<Box>,
    @JvmField val floorY: Float,
    @JvmField val goalPosition: Vec3,
    @JvmField val goalRadius: Float,
    /** Rectángulo exterior del tablero (incluye el perímetro), en el plano XZ. */
    @JvmField val minX: Float,
    @JvmField val maxX: Float,
    @JvmField val minZ: Float,
    @JvmField val maxZ: Float
) {
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f
    val width: Float get() = maxX - minX
    val depth: Float get() = maxZ - minZ
}
