package com.perplexus.game.physics

import com.perplexus.game.math.Vec3

/** Estado del sistema no inercial E en un instante (todo expresado en E). */
class FrameState {
    /** Aceleración gravitatoria efectiva (apunta "hacia abajo") en E, m/s². */
    @JvmField val gravity = Vec3(0f, -PhysicsConstants.GRAVITY, 0f)
    /** Velocidad angular de E respecto al sistema inercial, expresada en E (rad/s). */
    @JvmField val omega = Vec3()
    /** Aceleración angular de E (rad/s²). */
    @JvmField val alpha = Vec3()

    fun set(o: FrameState): FrameState { gravity.set(o.gravity); omega.set(o.omega); alpha.set(o.alpha); return this }

    fun lerp(a: FrameState, b: FrameState, t: Float): FrameState {
        gravity.set(a.gravity).lerpTo(b.gravity, t)
        omega.set(a.omega).lerpTo(b.omega, t)
        alpha.set(a.alpha).lerpTo(b.alpha, t)
        return this
    }
}

/**
 * Fuerzas aplicadas a la bola. Convención de signos del plan §6.4:
 *   F_cf  = -m ω×(ω×p)       (centrífuga)
 *   F_cor = -2m ω×v          (Coriolis)
 *   F_eu  = -m α×p           (Euler)
 */
object Forces {
    private val t1 = Vec3()
    private val t2 = Vec3()

    @JvmStatic fun applyGravity(body: RigidBody, g: Vec3) {
        body.applyForce(g.x * body.mass, g.y * body.mass, g.z * body.mass)
    }

    @JvmStatic fun applyCentrifugal(body: RigidBody, omega: Vec3) {
        omega.crossInto(body.position, t1)      // ω × p
        omega.crossInto(t1, t2)                 // ω × (ω × p)
        val m = body.mass
        body.applyForce(-m * t2.x, -m * t2.y, -m * t2.z)
    }

    @JvmStatic fun applyCoriolis(body: RigidBody, omega: Vec3) {
        omega.crossInto(body.velocity, t1)      // ω × v
        val m = -2f * body.mass
        body.applyForce(m * t1.x, m * t1.y, m * t1.z)
    }

    @JvmStatic fun applyEuler(body: RigidBody, alpha: Vec3) {
        alpha.crossInto(body.position, t1)      // α × p
        val m = body.mass
        body.applyForce(-m * t1.x, -m * t1.y, -m * t1.z)
    }

    /** Amortiguamiento lineal y angular (multiplicativo, estable). */
    @JvmStatic fun applyDamping(body: RigidBody, linear: Float, angular: Float, dt: Float) {
        val kl = 1f - linear * dt
        val ka = 1f - angular * dt
        body.velocity.scale(if (kl < 0f) 0f else kl)
        body.angularVelocity.scale(if (ka < 0f) 0f else ka)
    }

    /** Aplica todas las fuerzas reales y ficticias de un paso. */
    @JvmStatic fun applyAll(body: RigidBody, f: FrameState) {
        applyGravity(body, f.gravity)
        applyCentrifugal(body, f.omega)
        applyCoriolis(body, f.omega)
        applyEuler(body, f.alpha)
    }

    /** Aceleración total (solo marco) sobre un cuerpo puntual: útil para tests. */
    @JvmStatic fun frameAcceleration(p: Vec3, v: Vec3, f: FrameState, out: Vec3): Vec3 {
        val cf = Vec3(); val cor = Vec3(); val eu = Vec3(); val tmp = Vec3()
        f.omega.crossInto(p, tmp); f.omega.crossInto(tmp, cf); cf.negate()
        f.omega.crossInto(v, cor); cor.scale(-2f)
        f.alpha.crossInto(p, eu); eu.negate()
        out.set(f.gravity).add(cf).add(cor).add(eu)
        return out
    }
}
