package com.laberinto3d.juego.game.physics

/**
 * Caja alineada a los ejes del mundo.
 * Convención: min.x < max.x, min.y < max.y, min.z < max.z.
 * Las paredes son cajas altas y finas; el suelo es una caja plana ([isFloor] = true).
 * Las cajas son inmutables una vez construidas (se comparten entre nivel, mundo y render).
 */
class Box(
    @JvmField val min: Vec3,
    @JvmField val max: Vec3,
    /** RGBA para el render. */
    @JvmField val color: FloatArray,
    @JvmField val isFloor: Boolean = false
) {
    init {
        require(min.x < max.x && min.y < max.y && min.z < max.z) { "Box inválida: min=$min max=$max" }
        require(color.size == 4) { "color debe tener 4 componentes" }
    }

    val centerX: Float get() = (min.x + max.x) * 0.5f
    val centerY: Float get() = (min.y + max.y) * 0.5f
    val centerZ: Float get() = (min.z + max.z) * 0.5f
    val sizeX: Float get() = max.x - min.x
    val sizeY: Float get() = max.y - min.y
    val sizeZ: Float get() = max.z - min.z

    override fun toString(): String = "Box($min .. $max${if (isFloor) " suelo" else ""})"
}
