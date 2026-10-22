package com.laberinto3d.juego.game.render

import com.laberinto3d.juego.game.core.Config
import com.laberinto3d.juego.game.physics.World
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cámara fija con perspectiva. Mira al centro del tablero desde un punto elevado hacia
 * el jugador (offset (0, sin e, cos e) * distancia). La distancia se calcula para que el
 * tablero entero quepa en la zona útil de pantalla (deja hueco arriba para el HUD).
 */
class Camera {
    val view = FloatArray(16)
    val proj = FloatArray(16)
    val viewProj = FloatArray(16)
    var eyeX = 0f; private set
    var eyeY = 0f; private set
    var eyeZ = 0f; private set
    var distance = 0f; private set

    private val tmp = FloatArray(3)

    /** Recalcula vista y proyección para [world] y la relación de aspecto [aspect]. */
    fun configure(world: World, aspect: Float) {
        val e = Math.toRadians(Config.CAMERA_ELEVATION_DEG.toDouble())
        val dy = sin(e).toFloat()
        val dz = cos(e).toFloat()
        val tx = world.centerX
        val ty = 0.05f
        val tz = world.centerZ

        // 8 esquinas del tablero (con altura de pared)
        val xs = floatArrayOf(world.minX, world.maxX)
        val ys = floatArrayOf(0f, Config.WALL_HEIGHT)
        val zs = floatArrayOf(world.minZ, world.maxZ)

        fun measure(d: Float, out: FloatArray) {
            val ex = tx
            val ey = ty + dy * d
            val ez = tz + dz * d
            Matrix.lookAt(view, ex, ey, ez, tx, ty, tz, 0f, 1f, 0f)
            Matrix.perspective(proj, Config.CAMERA_FOV_DEG, aspect, max(0.1f, d * 0.3f), d * 3f)
            Matrix.multiply(viewProj, proj, view)
            var minX = 9f; var maxX = -9f; var minY = 9f; var maxY = -9f
            for (x in xs) for (y in ys) for (z in zs) {
                Matrix.project(viewProj, x, y, z, tmp)
                minX = min(minX, tmp[0]); maxX = max(maxX, tmp[0])
                minY = min(minY, tmp[1]); maxY = max(maxY, tmp[1])
            }
            out[0] = minX; out[1] = maxX; out[2] = minY; out[3] = maxY
        }

        val ext = FloatArray(4)
        val allowedW = Config.FIT_MAX_X - Config.FIT_MIN_X
        val allowedH = Config.FIT_MAX_Y - Config.FIT_MIN_Y
        var lo = 1.5f
        var hi = 80f
        for (i in 0 until 40) {
            val mid = (lo + hi) * 0.5f
            measure(mid, ext)
            val w = ext[1] - ext[0]
            val h = ext[3] - ext[2]
            if (w <= allowedW && h <= allowedH) hi = mid else lo = mid
        }
        distance = hi
        measure(distance, ext)
        eyeX = tx
        eyeY = ty + dy * distance
        eyeZ = tz + dz * distance

        // Centrar el tablero dentro de la zona útil desplazando el punto principal
        val cxNow = (ext[0] + ext[1]) * 0.5f
        val cyNow = (ext[2] + ext[3]) * 0.5f
        val cxWant = (Config.FIT_MIN_X + Config.FIT_MAX_X) * 0.5f
        val cyWant = (Config.FIT_MIN_Y + Config.FIT_MAX_Y) * 0.5f
        Matrix.shiftNdc(proj, cxWant - cxNow, cyWant - cyNow)
        Matrix.multiply(viewProj, proj, view)
    }
}
