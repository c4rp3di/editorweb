package com.perplexus.game.core

import com.perplexus.game.levels.Level

/** Datos de la partida en curso: nivel, tiempo y marcas. El tiempo lo acumula el hilo GL. */
class Session {
    @Volatile var level: Level? = null
    @Volatile var levelIndex: Int = 0
    /** Tiempo jugado en el intento actual (ms), solo avanza en PLAYING. */
    @Volatile var elapsedMs: Long = 0L
    @Volatile var bestMs: Long = 0L
    @Volatile var attempts: Int = 0
    @Volatile var newRecord: Boolean = false

    fun startAttempt(l: Level, index: Int, best: Long) {
        level = l; levelIndex = index; bestMs = best
        elapsedMs = 0L; newRecord = false; attempts++
    }

    fun restartAttempt() { elapsedMs = 0L; newRecord = false; attempts++ }

    companion object {
        fun format(ms: Long): String {
            if (ms <= 0L) return "--:--.-"
            val totalTenths = ms / 100
            val tenths = (totalTenths % 10).toInt()
            val secs = ((totalTenths / 10) % 60).toInt()
            val mins = (totalTenths / 600).toInt()
            return "%02d:%02d.%d".format(mins, secs, tenths)
        }
    }
}
