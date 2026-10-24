package com.perplexus.game.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibración: pulsos breves en impactos y patrones largos en victoria/derrota. */
class Haptics(context: Context) {
    @Volatile var enabled = true

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Suppress("DEPRECATION")
    fun pulse(ms: Long, amplitude: Int = 150) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
        else v.vibrate(ms)
    }

    /** Intensidad según velocidad del impacto (solo impactos fuertes). */
    fun impact(speed: Float) {
        if (speed < 2.5f) return
        pulse((10 + speed * 2).toLong().coerceAtMost(40L), (80 + speed * 15).toInt())
    }

    @Suppress("DEPRECATION")
    private fun pattern(p: LongArray) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(p, -1)) else v.vibrate(p, -1)
    }

    fun victory() = pattern(longArrayOf(0, 60, 60, 60, 60, 160, 80, 260))
    fun defeat() = pattern(longArrayOf(0, 300, 80, 120, 60, 500))

    fun cancel() { vibrator?.cancel() }
}
