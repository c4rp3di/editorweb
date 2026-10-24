package com.perplexus.game.physics

import com.perplexus.game.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Solver de impulsos secuenciales (Gauss-Seidel proyectado) con impulsos acumulados,
 * fricción de Coulomb (estática/dinámica) en dos direcciones tangentes, restitución,
 * fricción de rodadura y corrección posicional tipo Baumgarte.
 */
class CollisionSolver {
    @JvmField var iterations: Int = PhysicsConstants.SOLVER_ITERATIONS
    @JvmField var beta: Float = PhysicsConstants.BAUMGARTE_BETA
    @JvmField var slop: Float = PhysicsConstants.PENETRATION_SLOP

    private val rc = Vec3()
    private val vrel = Vec3()
    private val wxr = Vec3()
    private val corr = Vec3()
    private val impulse = Vec3()
    private val torque = Vec3()

    /** Prepara contactos: base tangente y velocidad objetivo de restitución. */
    fun prepare(body: RigidBody, contacts: ContactBuffer) {
        for (i in 0 until contacts.count) {
            val c = contacts[i]
            val n = c.normal
            // Base tangente estable
            if (abs(n.x) < 0.57735f) c.tangent1.set(0f, -n.z, n.y) else c.tangent1.set(-n.z, 0f, n.x)
            c.tangent1.normalize()
            n.crossInto(c.tangent1, c.tangent2)
            c.tangent2.normalize()

            contactVelocity(body, n, vrel)
            val vn = vrel.dot(n)
            c.preSolveNormalSpeed = -vn
            c.bias = if (vn < -PhysicsConstants.RESTITUTION_THRESHOLD) -c.restitution * vn else 0f
            c.normalImpulse = 0f; c.tangentImpulse1 = 0f; c.tangentImpulse2 = 0f
        }
    }

    /** Velocidad del punto de contacto: v + ω × r_c con r_c = -n·radio. */
    private fun contactVelocity(body: RigidBody, n: Vec3, out: Vec3) {
        rc.set(n).scale(-body.radius)
        body.angularVelocity.crossInto(rc, wxr)
        out.set(body.velocity).add(wxr)
    }

    fun solve(body: RigidBody, contacts: ContactBuffer) {
        val invM = body.invMass
        val invI = body.invInertiaScalar
        val r = body.radius
        // Masa efectiva tangencial: 1 / (1/m + r²/I)
        val tangentDen = invM + r * r * invI
        val tangentMass = if (tangentDen > 0f) 1f / tangentDen else 0f
        val normalMass = if (invM > 0f) 1f / invM else 0f

        for (iter in 0 until iterations) {
            for (i in 0 until contacts.count) {
                val c = contacts[i]
                val n = c.normal

                // ---- Normal
                contactVelocity(body, n, vrel)
                val vn = vrel.dot(n)
                var dj = -(vn - c.bias) * normalMass
                val newJ = if (c.normalImpulse + dj > 0f) c.normalImpulse + dj else 0f
                dj = newJ - c.normalImpulse
                c.normalImpulse = newJ
                body.applyLinearImpulse(n.x * dj, n.y * dj, n.z * dj)
                // r_c × n = 0 para una esfera: la normal no genera torque.

                // ---- Fricción (cono de Coulomb)
                contactVelocity(body, n, vrel)
                val vt1 = vrel.dot(c.tangent1)
                val vt2 = vrel.dot(c.tangent2)
                var j1 = c.tangentImpulse1 - vt1 * tangentMass
                var j2 = c.tangentImpulse2 - vt2 * tangentMass
                val maxStatic = c.staticFriction * c.normalImpulse
                val len = sqrt(j1 * j1 + j2 * j2)
                if (len > maxStatic) {
                    // Deslizamiento: se limita a la fricción dinámica
                    val maxDyn = c.dynamicFriction * c.normalImpulse
                    val s = if (len > 1e-12f) maxDyn / len else 0f
                    j1 *= s; j2 *= s
                }
                val d1 = j1 - c.tangentImpulse1
                val d2 = j2 - c.tangentImpulse2
                c.tangentImpulse1 = j1
                c.tangentImpulse2 = j2
                impulse.set(0f, 0f, 0f)
                    .addScaled(c.tangent1, d1)
                    .addScaled(c.tangent2, d2)
                body.applyLinearImpulse(impulse.x, impulse.y, impulse.z)
                // Torque = r_c × P
                rc.set(n).scale(-r)
                rc.crossInto(impulse, torque)
                body.angularVelocity.addScaled(torque, invI)
            }
        }
    }

    /** Fricción de rodadura: frena la componente tangencial de ω hasta μ_roll·|J_n|·r/I. */
    fun applyRollingResistance(body: RigidBody, contacts: ContactBuffer) {
        val invI = body.invInertiaScalar
        for (i in 0 until contacts.count) {
            val c = contacts[i]
            if (c.normalImpulse <= 0f || c.rolling <= 0f) continue
            val n = c.normal
            val wn = body.angularVelocity.dot(n)
            // Componente tangencial de ω
            val tx = body.angularVelocity.x - n.x * wn
            val ty = body.angularVelocity.y - n.y * wn
            val tz = body.angularVelocity.z - n.z * wn
            val tl = sqrt(tx * tx + ty * ty + tz * tz)
            if (tl < 1e-6f) continue
            val maxDelta = c.rolling * c.normalImpulse * body.radius * invI
            val d = if (maxDelta < tl) maxDelta else tl
            val k = d / tl
            body.angularVelocity.x -= tx * k
            body.angularVelocity.y -= ty * k
            body.angularVelocity.z -= tz * k
        }
    }

    /** Corrección de penetración (Baumgarte posicional). Devuelve la penetración máxima previa. */
    fun correctPositions(body: RigidBody, contacts: ContactBuffer): Float {
        corr.setZero()
        var maxPen = 0f
        for (i in 0 until contacts.count) {
            val c = contacts[i]
            val n = c.normal
            val pen = c.penetration - corr.dot(n)
            if (c.penetration > maxPen) maxPen = c.penetration
            if (pen > slop) {
                var k = beta * (pen - slop)
                if (k > PhysicsConstants.MAX_CORRECTION) k = PhysicsConstants.MAX_CORRECTION
                corr.addScaled(n, k)
            }
        }
        body.position.add(corr)
        return maxPen
    }
}
