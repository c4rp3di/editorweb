package com.carpe.microlisto.data

import android.content.Context

/**
 * Ajustes de diarización guardados en SharedPreferences. Se leen al iniciar
 * una grabación y se le pasan al Diarizer. Permiten afinar sin recompilar.
 */
object AjustesDiarizacion {

    private const val PREFS = "microlisto_prefs"
    private const val KEY_HABLANTES = "diar_num_hablantes"
    private const val KEY_UMBRAL = "diar_umbral"
    private const val KEY_MIN_FRAG = "diar_min_frag_ms"
    private const val KEY_GAP = "diar_gap_ms"
    private const val KEY_HOP = "diar_hop_ms"

    const val HABLANTES_AUTO = 0

    fun getNumHablantes(c: Context): Int =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HABLANTES, HABLANTES_AUTO)

    fun getUmbral(c: Context): Float =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_UMBRAL, 0.55f)

    fun getMinFragMs(c: Context): Long =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MIN_FRAG, 2000L)

    fun getGapMs(c: Context): Long =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_GAP, 500L)

    fun getHopMs(c: Context): Int =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HOP, 5000)

    fun guardar(
        c: Context,
        numHablantes: Int,
        umbral: Float,
        minFragMs: Long,
        gapMs: Long,
        hopMs: Int
    ) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_HABLANTES, numHablantes)
            .putFloat(KEY_UMBRAL, umbral)
            .putLong(KEY_MIN_FRAG, minFragMs)
            .putLong(KEY_GAP, gapMs)
            .putInt(KEY_HOP, hopMs)
            .apply()
    }

    fun resetear(c: Context) {
        guardar(c, HABLANTES_AUTO, 0.55f, 2000L, 500L, 5000)
    }
}