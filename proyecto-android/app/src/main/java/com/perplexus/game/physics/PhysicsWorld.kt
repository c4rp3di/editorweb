package com.perplexus.game.physics

import com.perplexus.game.math.IntList
import com.perplexus.game.math.Quat
import com.perplexus.game.math.Vec3
import kotlin.math.ceil

/** Eventos que el mundo notifica (siempre desde el hilo de física/GL). */
interface PhysicsListener {
    fun onImpact(speed: Float, x: Float, y: Float, z: Float)
    fun onGoalReached()
    fun onFellIntoVoid()
    fun onEmergencyReset(reason: String)
}

enum class Outcome { RUNNING, WON, LOST }

/**
 * Mundo de física: una bola (cuerpo rígido) dentro del sistema no inercial E
 * (la esfera del juego). Avanza con paso fijo de 1/240 s.
 */
class PhysicsWorld {
    @JvmField val ball = RigidBody()
    @JvmField val frame = FrameState()          // estado de marco usado en el paso actual
    private val frameFrom = FrameState()
    private val frameTo = FrameState()

    private val narrowphase = Narrowphase()
    private val broadphase = Broadphase()
    private val solver = CollisionSolver()
    private val contacts = ContactBuffer()
    private val candidates = IntList(32)
    private val displacement = Vec3()

    private var shell: Shell? = null
    private var shapes: List<Shape> = emptyList()
    private val start = Vec3()
    private val goal = Vec3()
    private var goalRadius = 0.05f

    @JvmField var listener: PhysicsListener? = null
    @JvmField var linearDamping = PhysicsConstants.LINEAR_DAMPING
    @JvmField var angularDamping = PhysicsConstants.ANGULAR_DAMPING
    var iterations: Int
        get() = solver.iterations
        set(v) { solver.iterations = v }

    var outcome: Outcome = Outcome.RUNNING
        private set

    // Interpolación de render
    private var accumulator = 0f
    @JvmField var alpha = 0f

    // Estadísticas
    @JvmField var lastContactCount = 0
    @JvmField var lastStepCount = 0
    @JvmField var totalSteps = 0L
    @JvmField var lastMaxPenetration = 0f
    @JvmField var inContact = false
    @JvmField var lastImpactSpeed = 0f
    @JvmField var emergencyResets = 0

    private var impactCooldown = 0f
    private var saturatedTime = 0f
    private var simTime = 0f

    val shapeCount: Int get() = shapes.size
    val bvhNodes: Int get() = broadphase.nodeCount

    /** Configura el mundo con la geometría de un nivel. La bola se coloca en el inicio. */
    fun setup(
        worldShapes: List<Shape>, shellShape: Shell, startPos: Vec3, goalPos: Vec3,
        goalRad: Float, ballRadius: Float = PhysicsConstants.BALL_RADIUS, ballMass: Float = PhysicsConstants.BALL_MASS
    ) {
        shapes = worldShapes
        shell = shellShape
        broadphase.build(worldShapes)
        shellShape.id = worldShapes.size
        start.set(startPos); goal.set(goalPos); goalRadius = goalRad
        ball.setMassAndRadius(ballMass, ballRadius)
        narrowphase.ball = Material.BALL_STEEL
        resetBall()
    }

    fun resetBall() {
        ball.resetState(start.x, start.y, start.z)
        outcome = Outcome.RUNNING
        accumulator = 0f; alpha = 0f
        impactCooldown = 0f; saturatedTime = 0f; simTime = 0f
        inContact = false
        lastImpactSpeed = 0f
        frame.gravity.set(frameTo.gravity)
        frame.omega.set(frameTo.omega)
        frame.alpha.set(frameTo.alpha)
    }

    /** Fija el marco para el próximo update (objetivo); se interpola desde el anterior. */
    fun setFrameTarget(gx: Float, gy: Float, gz: Float, wx: Float, wy: Float, wz: Float, ax: Float, ay: Float, az: Float) {
        frameFrom.set(frameTo)
        frameTo.gravity.set(gx, gy, gz)
        frameTo.omega.set(wx, wy, wz)
        // El plan limita |α| ≤ 200 rad/s²
        frameTo.alpha.set(ax, ay, az).clampLength(PhysicsConstants.MAX_FRAME_ANGULAR_ACCEL)
    }

    /** Avanza el tiempo de render dt; ejecuta N pasos fijos. Devuelve N. */
    fun update(frameDt: Float): Int {
        if (outcome != Outcome.RUNNING) { lastStepCount = 0; return 0 }
        var dt = frameDt
        if (dt > PhysicsConstants.MAX_FRAME_DT) dt = PhysicsConstants.MAX_FRAME_DT
        if (dt < 0f) dt = 0f
        accumulator += dt
        var steps = (accumulator / PhysicsConstants.FIXED_DT).toInt()
        if (steps > PhysicsConstants.MAX_STEPS_PER_UPDATE) {
            steps = PhysicsConstants.MAX_STEPS_PER_UPDATE
            accumulator = 0f
        } else {
            accumulator -= steps * PhysicsConstants.FIXED_DT
        }
        for (i in 1..steps) {
            frame.lerp(frameFrom, frameTo, i.toFloat() / steps.toFloat())
            step(PhysicsConstants.FIXED_DT)
            if (outcome != Outcome.RUNNING) break
        }
        alpha = accumulator / PhysicsConstants.FIXED_DT
        lastStepCount = steps
        return steps
    }

    /** Un paso físico completo de duración dt (ver plan §6.5). */
    fun step(dt: Float) {
        val b = ball
        b.savePrevious()
        totalSteps++
        simTime += dt
        if (impactCooldown > 0f) impactCooldown -= dt

        // CCD por subdivisión adaptativa: limita el desplazamiento por subpaso
        val speed = b.velocity.length()
        var sub = 1
        if (speed > PhysicsConstants.CCD_MIN_SPEED) {
            val maxMove = PhysicsConstants.CCD_MAX_MOVE_FRACTION * b.radius
            val s = ceil(speed * dt / maxMove).toInt()
            sub = if (s < 1) 1 else if (s > PhysicsConstants.CCD_MAX_SUBSTEPS) PhysicsConstants.CCD_MAX_SUBSTEPS else s
        }
        val h = dt / sub.toFloat()
        var anyContact = false
        var saturated = false
        lastContactCount = 0

        for (k in 0 until sub) {
            // 1-4: fuerzas (gravedad + ficticias), amortiguamiento
            b.clearAccumulators()
            Forces.applyAll(b, frame)
            if (PhysicsConstants.SPIN_FRAME_COUPLING) {
                // dω_rel/dt = -α_E - ω_E × ω_rel
                val w = b.angularVelocity
                val cx = frame.omega.y * w.z - frame.omega.z * w.y
                val cy = frame.omega.z * w.x - frame.omega.x * w.z
                val cz = frame.omega.x * w.y - frame.omega.y * w.x
                w.x -= (frame.alpha.x + cx) * h; w.y -= (frame.alpha.y + cy) * h; w.z -= (frame.alpha.z + cz) * h
            }
            // 5: integrar velocidad
            b.integrateVelocities(h)
            Forces.applyDamping(b, linearDamping, angularDamping, h)
            if (b.clampVelocities(PhysicsConstants.MAX_SPEED, PhysicsConstants.MAX_ANGULAR_SPEED)) saturated = true
            // 6: integrar posición/orientación
            b.integratePositions(h)

            // 7-8: broadphase + narrowphase
            collectContacts(h)
            val n = contacts.count
            if (n > 0) {
                anyContact = true
                if (n > lastContactCount) lastContactCount = n
                // 9: solver
                solver.prepare(b, contacts)
                solver.solve(b, contacts)
                solver.applyRollingResistance(b, contacts)
                // 10: corrección de penetración
                lastMaxPenetration = solver.correctPositions(b, contacts)
                handleContactEvents()
                if (outcome != Outcome.RUNNING) return
            }
        }
        inContact = anyContact

        // 11: orientación ya renormalizada en integratePositions
        // Seguridad numérica
        if (!b.isFinite()) { emergencyReset("estado no finito"); return }
        saturatedTime = if (saturated) saturatedTime + dt else 0f
        if (saturatedTime > PhysicsConstants.RUNAWAY_SECONDS) { emergencyReset("velocidad saturada"); return }

        // 12: meta
        val dx = b.position.x - goal.x; val dy = b.position.y - goal.y; val dz = b.position.z - goal.z
        if (dx * dx + dy * dy + dz * dz <= goalRadius * goalRadius) {
            outcome = Outcome.WON
            listener?.onGoalReached()
        }
    }

    private fun collectContacts(h: Float) {
        contacts.clear()
        val b = ball
        displacement.set(b.velocity).scale(h)
        broadphase.query(b.position, b.radius, displacement, 0.01f, candidates)
        for (i in 0 until candidates.size) {
            narrowphase.collide(broadphase.shape(candidates[i]), b.position, b.radius, contacts)
        }
        val sh = shell
        if (sh != null) narrowphase.collide(sh, b.position, b.radius, contacts)
    }

    private fun handleContactEvents() {
        var maxImpact = 0f
        var ix = 0f; var iy = 0f; var iz = 0f
        for (i in 0 until contacts.count) {
            val c = contacts[i]
            if (c.lethal) {
                outcome = Outcome.LOST
                listener?.onFellIntoVoid()
                return
            }
            if (c.preSolveNormalSpeed > maxImpact) {
                maxImpact = c.preSolveNormalSpeed
                ix = c.point.x; iy = c.point.y; iz = c.point.z
            }
        }
        if (maxImpact > 0.5f && impactCooldown <= 0f) {
            impactCooldown = 0.06f
            lastImpactSpeed = maxImpact
            listener?.onImpact(maxImpact, ix, iy, iz)
        }
    }

    private fun emergencyReset(reason: String) {
        emergencyResets++
        val l = listener
        resetBall()
        l?.onEmergencyReset(reason)
    }

    /** Posición interpolada para el render. */
    fun renderPosition(out: Vec3): Vec3 {
        val a = if (alpha < 0f) 0f else if (alpha > 1f) 1f else alpha
        out.set(ball.prevPosition).lerpTo(ball.position, a)
        return out
    }

    /** Orientación interpolada (nlerp) para el render. */
    fun renderOrientation(out: Quat): Quat {
        val a = if (alpha < 0f) 0f else if (alpha > 1f) 1f else alpha
        val p = ball.prevOrientation; val q = ball.orientation
        var sign = 1f
        if (p.dot(q) < 0f) sign = -1f
        out.set(p.w + (sign * q.w - p.w) * a, p.x + (sign * q.x - p.x) * a, p.y + (sign * q.y - p.y) * a, p.z + (sign * q.z - p.z) * a)
        return out.normalize()
    }

    fun distanceToGoal(): Float = ball.position.distanceTo(goal)
    fun speed(): Float = ball.velocity.length()
    fun startPosition(): Vec3 = start
    fun goalPosition(): Vec3 = goal
    fun contactBuffer(): ContactBuffer = contacts
}
