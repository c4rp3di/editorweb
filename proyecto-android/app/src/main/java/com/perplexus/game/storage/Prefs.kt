package com.perplexus.game.storage

import android.content.Context
import android.content.SharedPreferences
import com.perplexus.game.input.BiasStorage

/** SharedPreferences tipadas. Los niveles se identifican por id de texto. */
object Prefs : BiasStorage {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(PrefsKeys.FILE, Context.MODE_PRIVATE)
    }

    fun getBestTime(levelId: String): Long = sp.getLong(PrefsKeys.BEST_TIME_PREFIX + levelId, 0L)

    /** Guarda si mejora la marca. Devuelve true si es nuevo récord. */
    fun setBestTime(levelId: String, ms: Long): Boolean {
        val cur = getBestTime(levelId)
        if (cur == 0L || ms < cur) { sp.edit().putLong(PrefsKeys.BEST_TIME_PREFIX + levelId, ms).apply(); return true }
        return false
    }

    /** Índice (0-based) del nivel más alto desbloqueado. */
    fun getUnlockedLevel(): Int = sp.getInt(PrefsKeys.UNLOCKED_LEVEL, 0)
    fun unlockLevel(index: Int) { if (index > getUnlockedLevel()) sp.edit().putInt(PrefsKeys.UNLOCKED_LEVEL, index).apply() }

    fun getSensitivity(): Float = sp.getFloat(PrefsKeys.SETTING_SENSITIVITY, 1f)
    fun setSensitivity(v: Float) { sp.edit().putFloat(PrefsKeys.SETTING_SENSITIVITY, v).apply() }
    fun getSoundEnabled(): Boolean = sp.getBoolean(PrefsKeys.SETTING_SOUND, true)
    fun setSoundEnabled(v: Boolean) { sp.edit().putBoolean(PrefsKeys.SETTING_SOUND, v).apply() }
    fun getVibrationEnabled(): Boolean = sp.getBoolean(PrefsKeys.SETTING_VIBRATION, true)
    fun setVibrationEnabled(v: Boolean) { sp.edit().putBoolean(PrefsKeys.SETTING_VIBRATION, v).apply() }

    fun getCalibrationBias(): FloatArray = floatArrayOf(
        sp.getFloat(PrefsKeys.CAL_BIAS_X, 0f), sp.getFloat(PrefsKeys.CAL_BIAS_Y, 0f), sp.getFloat(PrefsKeys.CAL_BIAS_Z, 0f)
    )
    fun setCalibrationBias(b: FloatArray) {
        sp.edit().putFloat(PrefsKeys.CAL_BIAS_X, b[0]).putFloat(PrefsKeys.CAL_BIAS_Y, b[1]).putFloat(PrefsKeys.CAL_BIAS_Z, b[2]).apply()
    }
    override fun loadBias(): FloatArray = getCalibrationBias()
    override fun saveBias(b: FloatArray) = setCalibrationBias(b)

    fun getLastCrashSeen(): Long = sp.getLong(PrefsKeys.LAST_CRASH_SEEN, 0L)
    fun setLastCrashSeen(ts: Long) { sp.edit().putLong(PrefsKeys.LAST_CRASH_SEEN, ts).apply() }

    fun getRandomDifficulty(): Int = sp.getInt(PrefsKeys.RANDOM_DIFFICULTY, 3)
    fun setRandomDifficulty(v: Int) { sp.edit().putInt(PrefsKeys.RANDOM_DIFFICULTY, v).apply() }

    fun clearRecords() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith(PrefsKeys.BEST_TIME_PREFIX)) e.remove(k)
        e.putInt(PrefsKeys.UNLOCKED_LEVEL, 0)
        e.apply()
    }
}
