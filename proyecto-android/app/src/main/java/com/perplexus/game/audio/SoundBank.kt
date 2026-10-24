package com.perplexus.game.audio

import android.content.Context
import android.media.SoundPool
import com.perplexus.game.diag.GameLog

/** Carga los assets de sonido en un SoundPool y guarda sus identificadores. */
class SoundBank(private val context: Context, private val pool: SoundPool) {
    private val ids = HashMap<String, Int>()
    @Volatile var loaded = 0
        private set

    fun load(path: String) {
        try {
            context.assets.openFd(path).use { fd -> ids[path] = pool.load(fd, 1) }
        } catch (e: Exception) {
            GameLog.w("SoundBank", "No se pudo cargar $path", e)
        }
    }

    fun loadAll() {
        pool.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded++ }
        for (p in listOf(AudioConstants.BOUNCE, AudioConstants.ROLL, AudioConstants.GOAL, AudioConstants.LOSE, AudioConstants.TAP, AudioConstants.BUMP)) load(p)
    }

    fun id(path: String): Int = ids[path] ?: 0
    val count: Int get() = ids.size
}
