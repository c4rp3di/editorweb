package com.perplexus.game.physics

import com.perplexus.game.math.Mat3
import com.perplexus.game.math.Quat
import com.perplexus.game.math.Vec3

/**
 * Cuerpo rígido esférico. Todas las magnitudes están expresadas en el sistema E
 * (el de la esfera del juego / dispositivo, no inercial).
 */
class RigidBody(
    @JvmField var mass: Float = PhysicsConstants.BALL_MASS,
    @JvmField var radius: Float = PhysicsConstants.BALL_RADIUS
) {
    @JvmField val position = Vec3()
    @JvmField val orientation = Quat()
    @JvmField val velocity = Vec3()
    @JvmField val angularVelocity = Vec3()

    @JvmField val force = Vec3()
    @JvmField val torque = Vec3()

    @JvmField var invMass: Float = 1f / mass
    /** Tensor de inercia en el sistema del cuerpo (esfera sólida: (2/5)·m·r²·I). */
    @JvmField val inertiaBody = Mat3()
    @JvmField val invInertiaBody = Mat3()
    /** Inversa escalar de la inercia (la esfera es isótropa). */
    @JvmField var invInertiaScalar: Float = 0f

    // Estado previo para interpolación visual
    @JvmField val prevPosition = Vec3()
    @JvmField val prevOrientation = Quat()

    init { recomputeMassProperties() }

    fun setMassAndRadius(m: Float, r: Float) {
        mass = m; radius = r
        recomputeMassProperties()
    }

    fun recomputeMassProperties() {
        invMass = if (mass > 0f) 1f / mass else 0f
        val i = 0.4f * mass * radius * radius
        inertiaBody.setDiagonal(i)
        invInertiaScalar = if (i > 0f) 1f / i else 0f
        invInertiaBody.setDiagonal(invInertiaScalar)
    }

    fun clearAccumulators() { force.setZero(); torque.setZero() }

    fun applyForce(fx: Float, fy: Float, fz: Float) { force.x += fx; force.y += fy; force.z += fz }
    fun applyForce(f: Vec3) { force.add(f) }
    fun applyTorque(t: Vec3) { torque.add(t) }

    /** Impulso lineal J aplicado en el centro de masas. */
    fun applyLinearImpulse(jx: Float, jy: Float, jz: Float) {
        velocity.x += jx * invMass; velocity.y += jy * invMass; velocity.z += jz * invMass
    }

    /** Integra velocidades con las fuerzas/torques acumulados. */
    fun integrateVelocities(dt: Float) {
        velocity.x += force.x * invMass * dt
        velocity.y += force.y * invMass * dt
        velocity.z += force.z * invMass * dt
        angularVelocity.x += torque.x * invInertiaScalar * dt
        angularVelocity.y += torque.y * invInertiaScalar * dt
        angularVelocity.z += torque.z * invInertiaScalar * dt
    }

    /** Integra posición y orientación. */
    fun integratePositions(dt: Float) {
        position.addScaled(velocity, dt)
        orientation.integrate(angularVelocity.x, angularVelocity.y, angularVelocity.z, dt)
    }

    fun savePrevious() {
        prevPosition.set(position)
        prevOrientation.set(orientation)
    }

    /** Limita velocidades; devuelve true si hubo saturación. */
    fun clampVelocities(maxSpeed: Float, maxAngular: Float): Boolean {
        var clamped = false
        if (velocity.lengthSq() > maxSpeed * maxSpeed) { velocity.clampLength(maxSpeed); clamped = true }
        if (angularVelocity.lengthSq() > maxAngular * maxAngular) { angularVelocity.clampLength(maxAngular); clamped = true }
        return clamped
    }

    fun kineticEnergy(): Float =
        0.5f * mass * velocity.lengthSq() + 0.5f * (0.4f * mass * radius * radius) * angularVelocity.lengthSq()

    fun isFinite(): Boolean =
        position.isFinite() && velocity.isFinite() && angularVelocity.isFinite() &&
            orientation.w.isFinite() && orientation.x.isFinite() && orientation.y.isFinite() && orientation.z.isFinite()

    fun resetState(px: Float, py: Float, pz: Float) {
        position.set(px, py, pz)
        velocity.setZero(); angularVelocity.setZero()
        orientation.setIdentity()
        clearAccumulators()
        savePrevious()
    }
}
