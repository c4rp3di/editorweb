package com.perplexus.game.input

import com.perplexus.game.math.Quat
import com.perplexus.game.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Filtro complementario de Mahony (giroscopio + acelerómetro).
 *
 * Convenciones:
 *  - q rota vectores del sistema del dispositivo (x derecha, y arriba de la pantalla, z hacia el usuario)
 *    al sistema del mundo (Z arriba). R(q)·v_dispositivo = v_mundo.
 *  - up_body(q) = R(q)ᵀ·(0,0,1) es la dirección "arriba" vista desde el dispositivo; en reposo el acelerómetro
 *    mide +g·up_body.
 *  - Error de inclinación e = a_n × up_body(q); ω_corr = ω − b + Kp·w·e.
 */
class SensorFusion(@JvmField var kp: Float = 1.5f, @JvmField var ki: Float = 0f) {
    @JvmField val q = Quat()
    @JvmField val bias = Vec3()
    /** ω − b (sin término proporcional), en el sistema del dispositivo. */
    @JvmField val omega = Vec3()
    @JvmField val upBody = Vec3()
    @JvmField var lastWeight: Float = 0f
    @JvmField var lastTiltErrorRad: Float = 0f

    init { updateUp() }

    fun updateUp() {
        upBody.set(
            2f * (q.x * q.z - q.w * q.y),
            2f * (q.w * q.x + q.y * q.z),
            q.w * q.w - q.x * q.x - q.y * q.y + q.z * q.z
        )
    }

    fun reset() { q.setIdentity(); bias.setZero(); omega.setZero(); updateUp() }

    /** Inicializa la orientación a partir de una medida del acelerómetro (inclinación; guiñada = 0). */
    fun initFromAccel(ax: Float, ay: Float, az: Float) {
        val a = Vec3(ax, ay, az)
        if (a.lengthSq() < 1e-6f) return
        // R(q)·a_n = (0,0,1)
        q.set(Quat.fromTwoVectors(a, Vec3(0f, 0f, 1f)))
        updateUp()
    }

    /**
     * Un paso del filtro. [accelValid] indica si (ax,ay,az) es utilizable. dt en segundos.
     * [kpScale] permite aumentar la ganancia (p. ej. si se ignora el giroscopio).
     */
    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, accelValid: Boolean, dt: Float, kpScale: Float = 1f) {
        if (dt <= 0f) return
        omega.set(gx - bias.x, gy - bias.y, gz - bias.z)
        var cx = omega.x; var cy = omega.y; var cz = omega.z
        lastWeight = 0f
        if (accelValid) {
            val norm = sqrt(ax * ax + ay * ay + az * az)
            if (norm > 0.5f) {
                val inv = 1f / norm
                val nx = ax * inv; val ny = ay * inv; val nz = az * inv
                val vx = upBody.x; val vy = upBody.y; val vz = upBody.z
                val dev = abs(norm - G)
                var w = if (dev <= GATE_LO) 1f else 1f - (dev - GATE_LO) / (GATE_HI - GATE_LO)
                if (w < 0f) w = 0f
                // Con el dispositivo casi invertido respecto a la medida el error es ambiguo
                val d = nx * vx + ny * vy + nz * vz
                lastTiltErrorRad = kotlin.math.acos(if (d > 1f) 1f else if (d < -1f) -1f else d)
                if (d < -0.95f) w = 0f
                val ex = ny * vz - nz * vy
                val ey = nz * vx - nx * vz
                val ez = nx * vy - ny * vx
                if (ki > 0f && w > 0f) {
                    bias.x -= ki * w * ex * dt
                    bias.y -= ki * w * ey * dt
                    bias.z -= ki * w * ez * dt
                }
                val k = kp * kpScale * w
                cx += k * ex; cy += k * ey; cz += k * ez
                lastWeight = w
            }
        }
        q.integrate(cx, cy, cz, dt)
        updateUp()
    }

    companion object {
        const val G = 9.81f
        const val GATE_LO = 0.3f
        const val GATE_HI = 1.0f
    }
}
