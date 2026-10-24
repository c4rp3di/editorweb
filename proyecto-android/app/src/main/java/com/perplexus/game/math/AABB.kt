package com.perplexus.game.math

import kotlin.math.max
import kotlin.math.min

/** Caja envolvente alineada a los ejes. */
class AABB(
    @JvmField var minX: Float = Float.MAX_VALUE, @JvmField var minY: Float = Float.MAX_VALUE, @JvmField var minZ: Float = Float.MAX_VALUE,
    @JvmField var maxX: Float = -Float.MAX_VALUE, @JvmField var maxY: Float = -Float.MAX_VALUE, @JvmField var maxZ: Float = -Float.MAX_VALUE
) {
    fun reset(): AABB {
        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; minZ = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE; maxZ = -Float.MAX_VALUE
        return this
    }

    fun set(a: AABB): AABB {
        minX = a.minX; minY = a.minY; minZ = a.minZ; maxX = a.maxX; maxY = a.maxY; maxZ = a.maxZ
        return this
    }

    fun set(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): AABB {
        minX = x0; minY = y0; minZ = z0; maxX = x1; maxY = y1; maxZ = z1
        return this
    }

    fun copy(): AABB = AABB(minX, minY, minZ, maxX, maxY, maxZ)

    fun isValid(): Boolean = minX <= maxX && minY <= maxY && minZ <= maxZ

    fun expandPoint(x: Float, y: Float, z: Float): AABB {
        minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
        maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
        return this
    }
    fun expandPoint(p: Vec3): AABB = expandPoint(p.x, p.y, p.z)

    fun union(o: AABB): AABB {
        minX = min(minX, o.minX); minY = min(minY, o.minY); minZ = min(minZ, o.minZ)
        maxX = max(maxX, o.maxX); maxY = max(maxY, o.maxY); maxZ = max(maxZ, o.maxZ)
        return this
    }

    /** Engorda la caja en todas las direcciones. */
    fun inflate(m: Float): AABB {
        minX -= m; minY -= m; minZ -= m; maxX += m; maxY += m; maxZ += m
        return this
    }

    fun intersects(o: AABB): Boolean =
        minX <= o.maxX && maxX >= o.minX &&
        minY <= o.maxY && maxY >= o.minY &&
        minZ <= o.maxZ && maxZ >= o.minZ

    fun contains(x: Float, y: Float, z: Float): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ
    fun contains(p: Vec3): Boolean = contains(p.x, p.y, p.z)

    fun contains(o: AABB): Boolean =
        o.minX >= minX && o.maxX <= maxX && o.minY >= minY && o.maxY <= maxY && o.minZ >= minZ && o.maxZ <= maxZ

    fun centerX(): Float = 0.5f * (minX + maxX)
    fun centerY(): Float = 0.5f * (minY + maxY)
    fun centerZ(): Float = 0.5f * (minZ + maxZ)
    fun extentX(): Float = maxX - minX
    fun extentY(): Float = maxY - minY
    fun extentZ(): Float = maxZ - minZ

    /** 0 = X, 1 = Y, 2 = Z */
    fun longestAxis(): Int {
        val ex = extentX(); val ey = extentY(); val ez = extentZ()
        return if (ex >= ey && ex >= ez) 0 else if (ey >= ez) 1 else 2
    }

    fun surfaceArea(): Float {
        val ex = extentX(); val ey = extentY(); val ez = extentZ()
        return 2f * (ex * ey + ey * ez + ez * ex)
    }

    /** Distancia al cuadrado desde un punto a la caja (0 si está dentro). */
    fun distanceSqToPoint(x: Float, y: Float, z: Float): Float {
        val dx = max(max(minX - x, 0f), x - maxX)
        val dy = max(max(minY - y, 0f), y - maxY)
        val dz = max(max(minZ - z, 0f), z - maxZ)
        return dx * dx + dy * dy + dz * dz
    }

    override fun toString(): String = "AABB[($minX,$minY,$minZ)-($maxX,$maxY,$maxZ)]"
}
