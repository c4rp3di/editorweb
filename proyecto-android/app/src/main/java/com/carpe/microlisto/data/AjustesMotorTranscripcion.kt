package com.carpe.microlisto.data

import android.content.Context

/**
 * Preferencias del motor de transcripción. Por defecto: Vosk.
 * Si algún día Whisper funciona, se podrá cambiar.
 */
object AjustesMotorTranscripcion {

    private const val PREFS = "microlisto_prefs"
    private const val KEY_MOTOR = "motor_transcripcion"

    const val VOSK = "vosk"
    const val WHISPER = "whisper"
    const val AUTO = "auto"

    // Si el modelo Vosk está descargado, se usa. Si no, se intenta Whisper.
    // Como Whisper tampoco funciona, en la práctica solo Vosk está operativo.
    fun getMotor(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MOTOR, VOSK) ?: VOSK

    fun setMotor(c: Context, motor: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MOTOR, motor)
            .apply()
    }
}