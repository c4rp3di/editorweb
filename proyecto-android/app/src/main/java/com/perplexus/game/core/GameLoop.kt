package com.perplexus.game.core

import com.perplexus.game.input.OrientationEstimator
import com.perplexus.game.math.Vec3
import com.perplexus.game.physics.Outcome
import com.perplexus.game.physics.PhysicsListener
import com.perplexus.game.physics.PhysicsWorld

/** Eventos de juego hacia el exterior (se emiten desde el hilo GL). */
interface GameEvents {
    fun onImpact(speed: Float, x: Float, y: Float, z: Float)
    fun onWon(timeMs: Long)
    fun onLost()
    fun onEmergencyReset(reason: String)
    /** Cada frame jugando: velocidad de rodadura (0 si no hay contacto). */
    fun onFrame(rollSpeed: Float)
}

/**
 * Bucle lógico por frame (hilo GL): lee sensores, alimenta la física con el marco no inercial,
 * avanza el tiempo y detecta victoria/derrota.
 */
class GameLoop(
    @JvmField val world: PhysicsWorld,
    @JvmField val estimator: OrientationEstimator,
    @JvmField val session: Session,
    @JvmField val machine: StateMachine
) : PhysicsListener {
    @JvmField var events: GameEvents? = null
    @Volatile var sensitivity: Float = 1f
    @Volatile var levelGravityScale: Float = 1f

    private val g = Vec3()
    private var lastNs = 0L
    private var pendingWon = false
    private var pendingLost = false
    @JvmField var lastDt = 0f
    @JvmField val gravityDebug = Vec3()

    init { world.listener = this }

    /** Reinicia el reloj (al volver de pausa, al cargar nivel…). */
    fun resetClock() { lastNs = 0L }

    fun tick(nowNs: Long) {
        var dt = if (lastNs == 0L) 0f else (nowNs - lastNs) * 1e-9f
        lastNs = nowNs
        if (dt > 0.1f) dt = 0.1f
        lastDt = dt

        estimator.update(nowNs)

        val st = machine.state
        // El marco (gravedad, ω, α) se actualiza siempre para que la esfera siga al móvil
        estimator.gravityPhysics(g, sensitivity * levelGravityScale)
        gravityDebug.set(g)
        val w = estimator.omega; val a = estimator.alpha
        world.setFrameTarget(g.x, g.y, g.z, w.x, w.y, w.z, a.x, a.y, a.z)

        if (st == GameState.PLAYING) {
            world.update(dt)
            events?.onFrame(if (world.inContact) world.speed() else 0f)
            session.elapsedMs += (dt * 1000f).toLong()
            if (pendingWon) { pendingWon = false; events?.onWon(session.elapsedMs) }
            if (pendingLost) { pendingLost = false; events?.onLost() }
        } else {
            // Congelada: no avanza física ni tiempo
            world.alpha = 0f
        }
    }

    // ---- PhysicsListener
    override fun onImpact(speed: Float, x: Float, y: Float, z: Float) { events?.onImpact(speed, x, y, z) }
    override fun onGoalReached() { pendingWon = true }
    override fun onFellIntoVoid() { pendingLost = true }
    override fun onEmergencyReset(reason: String) { events?.onEmergencyReset(reason) }

    val outcome: Outcome get() = world.outcome
}
