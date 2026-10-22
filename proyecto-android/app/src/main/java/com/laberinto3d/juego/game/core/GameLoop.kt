package com.laberinto3d.juego.game.core

import com.laberinto3d.juego.game.audio.AudioManager
import com.laberinto3d.juego.game.diag.GameLog
import com.laberinto3d.juego.game.input.AccelerometerController
import com.laberinto3d.juego.game.input.CalibrationStore
import com.laberinto3d.juego.game.input.TiltMapping
import com.laberinto3d.juego.game.physics.Vec3
import com.laberinto3d.juego.game.storage.Prefs
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp

/**
 * Orquestador del update. Se llama una vez por frame desde el hilo GL:
 * ejecuta las órdenes pendientes, lee el sensor, avanza la física con paso fijo,
 * detecta victoria/caída y publica datos al HUD. Devuelve el factor de interpolación.
 */
class GameLoop(private val accel: AccelerometerController) {
    private val commands = ConcurrentLinkedQueue<Runnable>()
    private val gravityTarget = Vec3(0f, -Config.GRAVITY, 0f)
    private val gravityNow = Vec3(0f, -Config.GRAVITY, 0f)
    private val angles = FloatArray(2)

    private var lastNanos = 0L
    private var acc = 0f
    private var lastSession: Session? = null
    private var lastHudMs = 0L
    private var physAccumNs = 0L
    private var physFrames = 0

    /** Encola una orden para ejecutarse en el hilo GL al inicio del siguiente frame. */
    fun post(r: Runnable) {
        commands.add(r)
    }

    /** Un frame. Devuelve alpha (0..1) para interpolar el render entre el paso anterior y el actual. */
    fun update(nowNanos: Long): Float {
        while (true) {
            val c = commands.poll() ?: break
            try { c.run() } catch (t: Throwable) { GameLog.e("Loop", "Orden falló", t) }
        }
        val s = GameState.currentSession ?: return 1f
        if (s !== lastSession) {
            lastSession = s
            acc = 0f
            lastNanos = nowNanos
            hookSession(s)
        }

        var dt = if (lastNanos == 0L) 0f else (nowNanos - lastNanos) * 1e-9f
        lastNanos = nowNanos
        if (dt < 0f) dt = 0f
        if (dt > Config.MAX_FRAME_TIME) {
            Telemetry.droppedTimeMs += ((dt - Config.MAX_FRAME_TIME) * 1000f).toLong()
            dt = Config.MAX_FRAME_TIME
        }

        // Sensor -> gravedad objetivo (cada frame, también fuera de PLAYING para el overlay)
        TiltMapping.compute(accel.filtered, CalibrationStore.neutral, accel.rotation, gravityTarget, angles)
        Telemetry.tiltRollDeg = angles[0]
        Telemetry.tiltPitchDeg = angles[1]

        var steps = 0
        var alpha = 1f
        if (s.phase == Phase.PLAYING) {
            val t0 = System.nanoTime()
            acc += dt
            val k = 1f - exp(-Config.STEP / Config.GRAVITY_SMOOTH_TAU)
            val p = s.physics
            while (acc >= Config.STEP) {
                gravityNow.x += (gravityTarget.x - gravityNow.x) * k
                gravityNow.y += (gravityTarget.y - gravityNow.y) * k
                gravityNow.z += (gravityTarget.z - gravityNow.z) * k
                p.gravity.set(gravityNow)
                p.step(Config.STEP)
                s.elapsedNanos += (Config.STEP * 1e9f).toLong()
                acc -= Config.STEP
                steps++
                if (p.won) { onWon(s); acc = 0f; break }
                if (p.fell) { onFell(s); acc = 0f; break }
            }
            alpha = (acc / Config.STEP).coerceIn(0f, 1f)
            physAccumNs += System.nanoTime() - t0
            physFrames++
            if (physFrames >= 30) {
                Telemetry.physicsMsAvg = physAccumNs / physFrames / 1e6f
                physAccumNs = 0L
                physFrames = 0
            }
        } else {
            acc = 0f
        }

        val b = s.ball
        Telemetry.stepsLastFrame = steps
        Telemetry.gx = gravityNow.x
        Telemetry.gy = gravityNow.y
        Telemetry.gz = gravityNow.z
        Telemetry.contactsLastStep = s.physics.contactsLastStep
        Telemetry.totalContacts = s.physics.totalContacts
        Telemetry.maxImpact = s.physics.maxImpactSeen
        Telemetry.stuckResets = s.physics.resetsByStuck

        // HUD ~cada 100 ms
        val nowMs = nowNanos / 1_000_000L
        if (nowMs - lastHudMs >= Config.HUD_PERIOD_MS) {
            lastHudMs = nowMs
            GameState.postHud(s)
        }
        if (b.position.y.isNaN()) {
            GameLog.e("Loop", "Posición NaN: se reinicia la bola")
            s.physics.resetBall()
        }
        return alpha
    }

    private fun hookSession(s: Session) {
        s.physics.impactListener = { speed, _, _ ->
            AudioManager.playBounce(speed)
            if (speed > Config.VIBRATE_MIN_SPEED) AudioManager.vibrate()
        }
        s.physics.warnListener = { msg -> GameLog.w("Physics", msg) }
        gravityNow.set(gravityTarget)
    }

    private fun onWon(s: Session) {
        val ms = s.elapsedMs
        val record = try { Prefs.setBestTime(s.levelId, ms) } catch (_: Throwable) { false }
        if (record) s.bestMs = ms
        s.lastResultMs = ms
        s.newRecord = record
        try {
            Prefs.unlockLevel(s.levelId + 1)
            GameState.unlockedLevel = Prefs.getUnlockedLevel()
        } catch (_: Throwable) { }
        GameLog.i("Loop", "Victoria nivel ${s.levelId}: ${ms}ms récord=$record")
        AudioManager.playGoal()
        AudioManager.vibrate(60L)
        GameState.transition(Phase.WON, "bola en la meta")
    }

    private fun onFell(s: Session) {
        GameLog.w("Loop", "La bola se salió del tablero en ${s.ball.position}")
        GameState.transition(Phase.LOST, "bola fuera del tablero")
    }
}
