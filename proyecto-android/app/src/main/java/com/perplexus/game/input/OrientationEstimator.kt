package com.perplexus.game.input

import com.perplexus.game.math.MathUtils
import com.perplexus.game.math.Quat
import com.perplexus.game.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Orientación del dispositivo como cuaternión + ω, α y gravedad física derivadas.
 * Se alimenta desde dos SampleRing (acelerómetro y giroscopio) en el hilo GL.
 */
class OrientationEstimator(private val accelRing: SampleRing, private val gyroRing: SampleRing) {
    @JvmField val fusion = SensorFusion(1.5f, 0f)
    @JvmField val calibrator = Calibrator()
    @JvmField val monitor = SensorMonitor()

    private val sa = SensorSample()
    private val sg = SensorSample()

    // Aceleración
    private var rawAx = 0f; private var rawAy = 0f; private var rawAz = SensorFusion.G
    private var fAx = 0f; private var fAy = 0f; private var fAz = SensorFusion.G
    private var haveAccel = false
    private var haveGyro = false
    private val residual = Vec3()   // aceleración lineal (lowpass) en sistema dispositivo

    // Velocidad / aceleración angular (sistema dispositivo)
    @JvmField val omega = Vec3()
    @JvmField val alpha = Vec3()
    private val omegaPrev = Vec3()
    private var lastGyroT = 0L
    private var lastUpdateNs = 0L

    // Estadísticas
    @JvmField var gyroHz = 0f
    @JvmField var accelHz = 0f
    private var rateWindowStart = 0L
    private var gCount = 0; private var aCount = 0
    @JvmField var lastLatencyMs = 0f

    // Heading de visualización
    private var cosPsi = 1f; private var sinPsi = 0f
    private val rot = FloatArray(9)

    private var needRecenter = false

    val ready: Boolean get() = haveAccel && haveGyro
    val quaternion: Quat get() = fusion.q
    val upBody: Vec3 get() = fusion.upBody

    fun setBias(b: FloatArray) { if (b.size >= 3) fusion.bias.set(b[0], b[1], b[2]) }

    /** Reinicia la orientación a partir del acelerómetro actual y el sesgo calibrado. */
    fun reinitialize() {
        fusion.q.setIdentity()
        if (haveAccel) fusion.initFromAccel(fAx, fAy, fAz)
        omega.setZero(); alpha.setZero(); omegaPrev.setZero(); residual.setZero()
        monitor.reset()
    }

    /** Tras reanudar la app: descarta datos antiguos y reorienta con el primer acelerómetro; el sesgo se conserva. */
    fun onSensorsRestarted() {
        accelRing.clear(); gyroRing.clear()
        lastGyroT = 0L; lastUpdateNs = 0L
        haveAccel = false; haveGyro = false
        omega.setZero(); alpha.setZero(); omegaPrev.setZero(); residual.setZero()
        monitor.reset()
        needRecenter = true
    }

    fun startCalibration() {
        calibrator.start()
    }

    /** Aplica el resultado de la calibración (sesgo + inclinación inicial) y recentra la vista. */
    fun applyCalibration() {
        fusion.bias.set(calibrator.bias)
        val g = calibrator.gravity
        fusion.q.setIdentity()
        if (g.lengthSq() > 1f) fusion.initFromAccel(g.x, g.y, g.z) else reinitialize()
        monitor.reset()
        recenter()
    }

    /** Drena los buffers de sensores en orden temporal y actualiza el estado. */
    fun update(nowNs: Long) {
        // Intercala acelerómetro y giroscopio por timestamp
        while (gyroRing.available() > 0) {
            val tg = gyroRing.peekTime()
            while (accelRing.available() > 0 && accelRing.peekTime() <= tg) {
                accelRing.pop(sa); processAccel()
            }
            gyroRing.pop(sg)
            processGyro()
        }
        while (accelRing.available() > 0) { accelRing.pop(sa); processAccel() }

        if (needRecenter && ready) { recenter(); needRecenter = false }

        // Aceleración angular (derivada filtrada) a ritmo de frame
        if (lastUpdateNs != 0L) {
            val dt = (nowNs - lastUpdateNs) * 1e-9f
            if (dt > 1e-4f && dt < 0.2f) {
                val k = 0.35f
                alpha.x += k * ((omega.x - omegaPrev.x) / dt - alpha.x)
                alpha.y += k * ((omega.y - omegaPrev.y) / dt - alpha.y)
                alpha.z += k * ((omega.z - omegaPrev.z) / dt - alpha.z)
                alpha.clampLength(200f)
            }
        }
        omegaPrev.set(omega)
        lastUpdateNs = nowNs
        if (lastGyroT != 0L) lastLatencyMs = (nowNs - lastGyroT) * 1e-6f

        // Frecuencias de muestreo (ventana de 1 s)
        if (rateWindowStart == 0L) rateWindowStart = nowNs
        if (nowNs - rateWindowStart >= 1_000_000_000L) {
            val s = (nowNs - rateWindowStart) * 1e-9f
            gyroHz = gCount / s; accelHz = aCount / s
            gCount = 0; aCount = 0; rateWindowStart = nowNs
        }
    }

    private fun processAccel() {
        rawAx = sa.x; rawAy = sa.y; rawAz = sa.z
        aCount++
        if (!haveAccel) {
            fAx = rawAx; fAy = rawAy; fAz = rawAz
            haveAccel = true
            if (!haveGyro) fusion.initFromAccel(fAx, fAy, fAz)
        } else {
            val a = ALPHA_LP
            fAx += a * (rawAx - fAx); fAy += a * (rawAy - fAy); fAz += a * (rawAz - fAz)
        }
        calibrator.addAccel(rawAx, rawAy, rawAz)
        // Residuo lineal: a_m − g·up_body, filtrado
        val up = fusion.upBody
        val rx = rawAx - SensorFusion.G * up.x
        val ry = rawAy - SensorFusion.G * up.y
        val rz = rawAz - SensorFusion.G * up.z
        residual.x += RESIDUAL_LP * (rx - residual.x)
        residual.y += RESIDUAL_LP * (ry - residual.y)
        residual.z += RESIDUAL_LP * (rz - residual.z)
        residual.clampLength(MAX_RESIDUAL)
    }

    private fun processGyro() {
        gCount++
        var dt = if (lastGyroT == 0L) 0f else (sg.t - lastGyroT) * 1e-9f
        lastGyroT = sg.t
        if (dt <= 0f) { haveGyro = true; return }
        if (dt > 0.05f) dt = 0.05f
        haveGyro = true
        calibrator.addGyro(sg.t, sg.x, sg.y, sg.z)

        val mag = sqrt(sg.x * sg.x + sg.y * sg.y + sg.z * sg.z)
        val an = sqrt(fAx * fAx + fAy * fAy + fAz * fAz)
        val reliable = abs(an - SensorFusion.G) < 0.5f
        val tiltDeg = MathUtils.toDeg(fusion.lastTiltErrorRad)
        val bad = monitor.update(dt, mag, tiltDeg, reliable)
        if (bad) {
            // Se ignora el giroscopio: solo corrección por acelerómetro
            fusion.update(0f, 0f, 0f, fAx, fAy, fAz, haveAccel, dt, 3f)
            omega.setZero()
        } else {
            fusion.update(sg.x, sg.y, sg.z, fAx, fAy, fAz, haveAccel, dt)
            omega.set(fusion.omega)
        }
    }

    /**
     * Gravedad efectiva en el sistema del dispositivo (m/s², apunta hacia abajo):
     * g = −(g·up_body + residuo_lineal_filtrado), escalada por [scale].
     */
    fun gravityPhysics(out: Vec3, scale: Float): Vec3 {
        val up = fusion.upBody
        out.set(
            -(SensorFusion.G * up.x + residual.x),
            -(SensorFusion.G * up.y + residual.y),
            -(SensorFusion.G * up.z + residual.z)
        )
        out.scale(scale)
        return out
    }

    /** Ajusta el rumbo de la vista para que el eje z del dispositivo apunte hacia el observador. */
    fun recenter() {
        val m = FloatArray(9)
        fusion.q.toMat3(m)
        val hx = m[2]; val hy = m[5]   // z del dispositivo en el mundo (horizontal)
        val l = sqrt(hx * hx + hy * hy)
        if (l > 0.3f) { cosPsi = -hy / l; sinPsi = hx / l }
    }

    /**
     * Rotación dispositivo→pantalla (3×3, fila mayor): X_display = M(ψ)·R(q)·X_device,
     * con el eje Y de la vista = vertical del mundo.
     */
    fun displayRotation(out: FloatArray) {
        fusion.q.toMat3(rot)
        for (j in 0 until 3) {
            val wx = rot[j]; val wy = rot[3 + j]; val wz = rot[6 + j]
            val x2 = cosPsi * wx + sinPsi * wy
            val y2 = -sinPsi * wx + cosPsi * wy
            out[0 * 3 + j] = x2
            out[1 * 3 + j] = wz
            out[2 * 3 + j] = -y2
        }
    }

    /** Vector "abajo" del mundo expresado en la vista (para depuración/HUD). */
    fun worldDownInDisplay(out: Vec3): Vec3 = out.set(0f, -1f, 0f)

    companion object {
        const val ALPHA_LP = 0.33f
        const val RESIDUAL_LP = 0.25f
        const val MAX_RESIDUAL = 12f
    }
}
