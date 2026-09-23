package com.carpe.microlisto.data

import android.content.Context

/**
 * Preferencias de Whisper: idioma de transcripción.
 * Se guardan aparte de AjustesDiarizacion porque son conceptos distintos.
 */
object AjustesWhisper {

    private const val PREFS = "microlisto_prefs"
    private const val KEY_IDIOMA = "whisper_idioma"

    // Códigos de idioma soportados. "auto" significa autodetección.
    const val AUTO = "auto"
    const val ESPANOL = "es"
    const val INGLES = "en"
    const val FRANCES = "fr"
    const val ALEMAN = "de"
    const val ITALIANO = "it"
    const val PORTUGUES = "pt"

    fun getIdioma(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_IDIOMA, ESPANOL) ?: ESPANOL

    fun setIdioma(c: Context, codigo: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_IDIOMA, codigo)
            .apply()
    }

    fun etiqueta(codigo: String): String = when (codigo) {
        AUTO -> "Auto (detectar)"
        ESPANOL -> "Español"
        INGLES -> "Inglés"
        FRANCES -> "Francés"
        ALEMAN -> "Alemán"
        ITALIANO -> "Italiano"
        PORTUGUES -> "Portugués"
        else -> codigo
    }
}