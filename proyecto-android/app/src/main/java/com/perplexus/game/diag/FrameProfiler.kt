package com.perplexus.game.diag

/** Medición de tiempos por fase del frame (hilo GL). */
class FrameProfiler {
    enum class Section { SENSORS, PHYSICS, SCENE, POST, FRAME }

    private val start = LongArray(Section.values().size)
    private val acc = DoubleArray(Section.values().size)       // ms acumulados en la ventana
    @JvmField val avgMs = FloatArray(Section.values().size)
    @JvmField var fps = 0f
    @JvmField var frames = 0L
    private var windowFrames = 0
    private var windowStart = 0L
    private var lastLog = 0L

    fun begin(s: Section) { start[s.ordinal] = System.nanoTime() }
    fun end(s: Section) { acc[s.ordinal] += (System.nanoTime() - start[s.ordinal]) * 1e-6 }

    /** Llamar al final de cada frame. */
    fun endFrame() {
        val now = System.nanoTime()
        frames++
        windowFrames++
        if (windowStart == 0L) { windowStart = now; lastLog = now }
        val el = now - windowStart
        if (el >= 500_000_000L) {
            fps = windowFrames * 1e9f / el
            for (i in acc.indices) { avgMs[i] = (acc[i] / windowFrames).toFloat(); acc[i] = 0.0 }
            windowFrames = 0
            windowStart = now
        }
        if (now - lastLog >= 5_000_000_000L) {
            lastLog = now
            GameLog.d("Perf", "fps=%.1f sensores=%.2fms fisica=%.2fms escena=%.2fms post=%.2fms frame=%.2fms".format(
                fps, avgMs[0], avgMs[1], avgMs[2], avgMs[3], avgMs[4]))
        }
    }
}
