package com.perplexus.game.physics

import com.perplexus.game.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tubo hueco recto (segmento A-B, radio interior [radius]). La bola rueda por el interior.
 * Puede tener una ranura (abertura) de semiángulo [openHalfAngle] orientada hacia fuera
 * del centro de la esfera: por ella la bola puede caer. Los extremos pueden estar tapados.
 */
class Tube(
    @JvmField val a: Vec3, @JvmField val b: Vec3, @JvmField val radius: Float,
    @JvmField val openHalfAngle: Float = 0f,
    @JvmField val capA: Boolean = false, @JvmField val capB: Boolean = false,
    @JvmField val shellCenter: Vec3 = Vec3(),
    material: Material = Material.METAL
) : Shape(material) {
    @JvmField val axis = Vec3()
    @JvmField var length: Float = 0f
    /** Identificador de cadena (polilínea) y posición dentro de ella; usado por el validador. */
    @JvmField var chainId: Int = -1
    @JvmField var chainIndex: Int = -1
    override val kind: ShapeKind get() = ShapeKind.TUBE

    init { computeAabb() }

    override fun computeAabb() {
        axis.set(b).sub(a)
        length = axis.normalize()
        aabb.reset().expandPoint(a).expandPoint(b).inflate(radius + 0.01f)
    }

    /**
     * Dirección (unitaria, ⟂ al eje) en la que se abre la ranura en el punto del eje situado a
     * distancia [s] de A. Es la dirección radial desde el centro de la esfera. Se comparte con el render.
     */
    fun slotDirection(s: Float, out: Vec3): Vec3 {
        val cx = a.x + axis.x * s - shellCenter.x
        val cy = a.y + axis.y * s - shellCenter.y
        val cz = a.z + axis.z * s - shellCenter.z
        val d = cx * axis.x + cy * axis.y + cz * axis.z
        out.x = cx - axis.x * d; out.y = cy - axis.y * d; out.z = cz - axis.z * d
        val l = sqrt(out.x * out.x + out.y * out.y + out.z * out.z)
        if (l < 1e-4f) {
            // El eje pasa por el centro: perpendicular arbitraria pero estable.
            if (abs(axis.y) < 0.9f) {
                out.x = -axis.z; out.y = 0f; out.z = axis.x
            } else {
                out.x = 0f; out.y = axis.z; out.z = -axis.y
            }
            val l2 = sqrt(out.x * out.x + out.y * out.y + out.z * out.z)
            if (l2 > 1e-9f) { out.x /= l2; out.y /= l2; out.z /= l2 }
        } else {
            out.x /= l; out.y /= l; out.z /= l
        }
        return out
    }

    /** Semiángulo efectivo de la ranura para una bola de radio [ballRadius] (0 si no cabe). */
    fun effectiveOpenAngle(ballRadius: Float): Float {
        if (openHalfAngle <= 0f) return 0f
        val e = openHalfAngle - ballRadius / radius
        return if (e > 0f) e else 0f
    }
}

/**
 * Codo/unión hueca entre un tubo entrante y uno o varios salientes. Solo actúa en la cuña exterior
 * (la bola ya pasó el final del entrante y aún no llegó al inicio de ningún saliente).
 */
class TubeJoint(
    @JvmField val center: Vec3, @JvmField val radius: Float,
    @JvmField val dirIn: Vec3, @JvmField val dirsOut: Array<Vec3>,
    material: Material = Material.METAL
) : Shape(material) {
    constructor(center: Vec3, radius: Float, dirIn: Vec3, dirOut: Vec3, material: Material = Material.METAL) :
        this(center, radius, dirIn, arrayOf(dirOut), material)

    override val kind: ShapeKind get() = ShapeKind.TUBE_JOINT
    init { computeAabb() }
    override fun computeAabb() {
        val e = radius + 0.01f
        aabb.set(center.x - e, center.y - e, center.z - e, center.x + e, center.y + e, center.z + e)
    }
}

/** Esfera exterior hueca (la bola nunca puede salir; tocarla significa haber caído al vacío). */
class Shell(
    @JvmField val center: Vec3, @JvmField val radius: Float, material: Material = Material.METAL
) : Shape(material) {
    override val kind: ShapeKind get() = ShapeKind.SHELL
    init { computeAabb() }
    override fun computeAabb() {
        aabb.set(center.x - radius, center.y - radius, center.z - radius, center.x + radius, center.y + radius, center.z + radius)
    }
}
