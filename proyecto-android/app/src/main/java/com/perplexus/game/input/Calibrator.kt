package com.perplexus.game.input

import com.perplexus.game.math.Vec3
import kotlin.math.sqrt

/**
 * Calibración inicial de 1 s con el móvil quieto: sesgo del giroscopio = media de ω.
 * Si hay movimiento (varianza alta) se repite, hasta 3 intentos.
 */
class Calibrator(private val durationNs: Long = 1_000_000_000L, private val maxAttempts: Int = 3) {
    enum class State { IDLE, COLLECTING, DONE, FAILED }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var attempt: Int = 0
        private set
    @Volatile var progress: Float = 0f
        private set
    @Volatile var message: String = ""
        private set
    @JvmField val bias = Vec3()
    @JvmField val gravity = Vec3()

    private var t0 = -1L
    private var n = 0
    private var mx = 0.0; private var my = 0.0; private var mz = 0.0
    private var m2x = 0.0; private var m2y = 0.0; private var m2z = 0.0
    private var an = 0
    private var ax = 0.0; private var ay = 0.0; private var az = 0.0

    fun start() {
        attempt = 1
        beginAttempt()
        message = "Mantén el móvil quieto…"
    }

    fun cancel() { state = State.IDLE; progress = 0f }

    private fun beginAttempt() {
        state = State.COLLECTING
        t0 = -1L; n = 0; an = 0; progress = 0f
        mx = 0.0; my = 0.0; mz = 0.0; m2x = 0.0; m2y = 0.0; m2z = 0.0
        ax = 0.0; ay = 0.0; az = 0.0
    }

    fun addAccel(x: Float, y: Float, z: Float) {
        if (state != State.COLLECTING) return
        ax += x; ay += y; az += z; an++
    }

    fun addGyro(t: Long, x: Float, y: Float, z: Float) {
        if (state != State.COLLECTING) return
        if (t0 < 0) t0 = t
        n++
        // Welford
        val dx = x - mx; mx += dx / n; m2x += dx * (x - mx)
        val dy = y - my; my += dy / n; m2y += dy * (y - my)
        val dz = z - mz; mz += dz / n; m2z += dz * (z - mz)
        progress = ((t - t0).toFloat() / durationNs.toFloat()).coerceIn(0f, 1f)
        if (t - t0 >= durationNs && n >= 10) finishAttempt()
    }

    private fun finishAttempt() {
        val sx = sqrt(m2x / n); val sy = sqrt(m2y / n); val sz = sqrt(m2z / n)
        val std = maxOf(sx, maxOf(sy, sz)).toFloat()
        val meanMag = sqrt(mx * mx + my * my + mz * mz).toFloat()
        if (std > MAX_STD || meanMag > MAX_MEAN) {
            if (attempt >= maxAttempts) {
                state = State.FAILED
                message = "No se pudo calibrar: había movimiento. Pulsa para reintentar."
            } else {
                attempt++
                beginAttempt()
                message = "Se detectó movimiento. Mantén el móvil quieto (intento $attempt/$maxAttempts)"
            }
            return
        }
        bias.set(mx.toFloat(), my.toFloat(), mz.toFloat())
        if (an > 0) gravity.set((ax / an).toFloat(), (ay / an).toFloat(), (az / an).toFloat())
        state = State.DONE
        progress = 1f
        message = "Calibración correcta"
    }

    companion object {
        const val MAX_STD = 0.035f
        const val MAX_MEAN = 0.25f
    }
}
