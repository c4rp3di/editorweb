package com.laberinto3d.juego.game.physics

import kotlin.math.abs

/** Rampa plana (para la sombra y el render). [dir]: 0 = sube hacia -Z, 1 = hacia +Z, 2 = hacia -X, 3 = hacia +X. */
class Ramp(
    @JvmField val x0: Float, @JvmField val z0: Float, @JvmField val x1: Float, @JvmField val z1: Float,
    @JvmField val dir: Int, @JvmField val yLow: Float, @JvmField val yHigh: Float
) {
    /** Altura de la superficie en (x, z) o NaN si queda fuera. */
    fun heightAt(x: Float, z: Float): Float {
        if (x < x0 || x > x1 || z < z0 || z > z1) return Float.NaN
        val t = when (dir) {
            0 -> (z1 - z) / (z1 - z0)
            1 -> (z - z0) / (z1 - z0)
            2 -> (x1 - x) / (x1 - x0)
            else -> (x - x0) / (x1 - x0)
        }
        return yLow + (yHigh - yLow) * t
    }
}

/** Plataforma móvil: oscila entre su posición base y base + (dx, dy, dz) con un periodo [period] s. */
class Mover(
    @JvmField val box: Box,
    @JvmField val dx: Float, @JvmField val dy: Float, @JvmField val dz: Float,
    @JvmField val period: Float, @JvmField val phase: Float
) {
    private val bminX = box.min.x; private val bminY = box.min.y; private val bminZ = box.min.z
    private val bmaxX = box.max.x; private val bmaxY = box.max.y; private val bmaxZ = box.max.z
    private var ox = 0f; private var oy = 0f; private var oz = 0f

    init { box.dynamic = true; update(0f, 0f) }

    /** Factor 0..1 de la posición en el instante [t] (con una parada del 25% del periodo en cada extremo). */
    fun factor(t: Float): Float {
        var u = (t / period + phase) % 1f
        if (u < 0f) u += 1f
        val tri = 1f - abs(2f * u - 1f)
        val a = ((tri - 0.25f) / 0.5f).coerceIn(0f, 1f)
        return a * a * (3f - 2f * a)
    }

    fun update(t: Float, dt: Float) {
        val s = factor(t)
        val nx = dx * s; val ny = dy * s; val nz = dz * s
        box.min.set(bminX + nx, bminY + ny, bminZ + nz)
        box.max.set(bmaxX + nx, bmaxY + ny, bmaxZ + nz)
        if (dt > 0f) {
            val nvx = (nx - ox) / dt; val nvz = (nz - oz) / dt
            box.dvx = nvx - box.vel.x; box.dvz = nvz - box.vel.z
            box.vel.set(nvx, (ny - oy) / dt, nvz)
        } else { box.vel.set(0f, 0f, 0f); box.dvx = 0f; box.dvz = 0f }
        ox = nx; oy = ny; oz = nz
    }
}

/**
 * Conjunto de colisionadores estáticos (cajas y triángulos) y dinámicos (plataformas móviles)
 * del nivel + datos de la meta. La meta no tiene colisión: sólo un punto y un radio.
 * Las plataformas móviles van las primeras de [boxes].
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
    @JvmField val topY: Float = 0.2f,
    /** Triángulos de rampas: 9 floats por triángulo (a, b, c) y 3 por normal. */
    @JvmField val tris: FloatArray = FloatArray(0),
    @JvmField val triNormals: FloatArray = FloatArray(0),
    @JvmField val ramps: List<Ramp> = emptyList(),
    @JvmField val movers: List<Mover> = emptyList()
) {
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f
    val width: Float get() = maxX - minX
    val depth: Float get() = maxZ - minZ
    val triCount: Int get() = tris.size / 9

    fun updateMovers(t: Float, dt: Float) {
        for (i in movers.indices) movers[i].update(t, dt)
    }

    /**
     * Altura de la superficie que hay justo debajo de (x, z), a partir de la altura [y]
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
        for (i in ramps.indices) {
            val h = ramps[i].heightAt(x, z)
            if (!h.isNaN() && h <= y + 0.05f && h > best) best = h
        }
        return best
    }

    companion object {
        const val NO_FLOOR = -1000f
    }
}
