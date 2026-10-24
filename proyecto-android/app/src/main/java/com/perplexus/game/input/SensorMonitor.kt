package com.perplexus.game.input

/**
 * Detecta que el giroscopio entrega datos basura (plan §8.5):
 *  - |ω| > 20 rad/s  → sospechoso.
 *  - Inclinación que difiere > 15° del acelerómetro durante 0.25 s → sospechoso.
 *  - Se recupera cuando concuerdan < 5° durante 0.5 s.
 */
class SensorMonitor {
    var suspicious: Boolean = false
        private set
    private var badTime = 0f
    private var goodTime = 0f
    var suspiciousEvents: Int = 0
        private set

    fun reset() { suspicious = false; badTime = 0f; goodTime = 0f }

    /**
     * @param omegaMag módulo de ω medido (rad/s)
     * @param tiltErrorDeg ángulo entre "arriba" medido por el acelerómetro y el estimado (grados)
     * @param accelReliable el acelerómetro mide ≈ g (sin sacudidas)
     * @return true si el giroscopio debe ignorarse
     */
    fun update(dt: Float, omegaMag: Float, tiltErrorDeg: Float, accelReliable: Boolean): Boolean {
        if (omegaMag > MAX_OMEGA) {
            if (!suspicious) suspiciousEvents++
            suspicious = true; goodTime = 0f
            return true
        }
        if (accelReliable) {
            if (tiltErrorDeg > BAD_DEG) {
                badTime += dt
                goodTime = 0f
                if (!suspicious && badTime >= BAD_SECONDS) { suspicious = true; suspiciousEvents++ }
            } else if (tiltErrorDeg < GOOD_DEG) {
                badTime = 0f
                if (suspicious) {
                    goodTime += dt
                    if (goodTime >= GOOD_SECONDS) { suspicious = false; goodTime = 0f }
                }
            } else {
                badTime = 0f; goodTime = 0f
            }
        }
        return suspicious
    }

    companion object {
        const val MAX_OMEGA = 20f
        const val BAD_DEG = 15f
        const val GOOD_DEG = 5f
        const val BAD_SECONDS = 0.25f
        const val GOOD_SECONDS = 0.5f
    }
}
