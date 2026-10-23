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
    @JvmField val maxZ: Float,
    /** Checkpoints en orden (y = cara superior del suelo donde están). */
    @JvmField val checkpoints: List<Vec3> = emptyList(),
    /** Altura máxima de cualquier caja (para encuadrar la cámara). */
    @JvmField val topY: Float = 0.2f
) {
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f
    val width: Float get() = maxX - minX
    val depth: Float get() = maxZ - minZ

    /**
     * Altura de la cara superior del suelo que hay justo debajo de (x, z), a partir de la altura [y]
     * (sólo cuenta superficies con tope <= y + 0.05). Devuelve [NO_FLOOR] si no hay nada debajo.
     * Se usa para colocar la sombra de la bola.
     */
    fun floorBelow(x: Float, z: Float, y: Float): Float {
        var best = NO_FLOOR
        for (i in boxes.indices) {
            val b = boxes[i]
            if (!b.isFloor) continue
            if (x < b.min.x || x > b.max.x || z < b.min.z || z > b.max.z) continue
            val top = b.max.y
            if (top <= y + 0.05f && top > best) best = top
        }
        return best
    }

    companion object {
        const val NO_FLOOR = -1000f
    }
}
