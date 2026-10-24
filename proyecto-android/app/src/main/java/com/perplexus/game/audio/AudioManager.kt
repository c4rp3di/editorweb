package com.perplexus.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.perplexus.game.diag.GameLog
import com.perplexus.game.math.MathUtils

/**
 * Efectos con SoundPool. La rodadura continua es un loop de SoundPool cuyo volumen y velocidad
 * (rate) se modulan con la velocidad de la bola (más fiable que MediaPlayer para cambiar el rate).
 */
object AudioManager {
    private var pool: SoundPool? = null
    private var bank: SoundBank? = null
    private var rollStream = 0
    private var rollRunning = false
    @Volatile private var enabled = true
    private var lastBounceMs = 0L

    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        pool = p
        val b = SoundBank(context.applicationContext, p)
        b.loadAll()
        bank = b
        GameLog.i("Audio", "SoundPool inicializado (${b.count} sonidos)")
    }

    fun setEnabled(e: Boolean) { enabled = e; if (!e) stopRoll() }
    fun isEnabled(): Boolean = enabled

    private fun play(path: String, vol: Float, rate: Float = 1f) {
        if (!enabled) return
        val p = pool ?: return
        val id = bank?.id(path) ?: 0
        if (id == 0) return
        p.play(id, vol, vol, 1, 0, rate)
    }

    fun playBounce(speed: Float) {
        val now = System.currentTimeMillis()
        if (now - lastBounceMs < 40) return
        lastBounceMs = now
        val vol = MathUtils.clamp(speed / 10f, AudioConstants.BOUNCE_MIN_VOL, 1f)
        if (speed < 1.2f) play(AudioConstants.BUMP, vol * 1.5f) else play(AudioConstants.BOUNCE, vol, 0.9f + 0.2f * MathUtils.clamp(speed / 6f, 0f, 1f))
    }

    fun playGoal() = play(AudioConstants.GOAL, 1f)
    fun playLose() = play(AudioConstants.LOSE, 1f)
    fun playTap() = play(AudioConstants.TAP, 0.7f)

    fun startRoll() {
        if (!enabled || rollRunning) return
        val p = pool ?: return
        val id = bank?.id(AudioConstants.ROLL) ?: 0
        if (id == 0) return
        rollStream = p.play(id, 0f, 0f, 1, -1, 1f)
        rollRunning = rollStream != 0
    }

    /** Modula el sonido de rodadura según la velocidad (m/s) de la bola en contacto. */
    fun updateRoll(speed: Float) {
        if (!enabled) return
        val p = pool ?: return
        if (!rollRunning) startRoll()
        if (!rollRunning) return
        if (speed < AudioConstants.ROLL_STOP_SPEED) { p.setVolume(rollStream, 0f, 0f); return }
        val rate = MathUtils.clamp(0.8f + 0.4f * (speed / 5f), 0.5f, 2f)
        val vol = MathUtils.clamp(speed / 3f, 0f, 0.6f)
        p.setRate(rollStream, rate)
        p.setVolume(rollStream, vol, vol)
    }

    fun stopRoll() {
        val p = pool ?: return
        if (rollRunning) { p.stop(rollStream); rollRunning = false; rollStream = 0 }
    }

    fun release() {
        stopRoll()
        pool?.release()
        pool = null; bank = null
    }
}
