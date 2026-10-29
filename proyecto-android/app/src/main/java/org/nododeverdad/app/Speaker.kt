package org.nododeverdad.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Lectura en voz alta con el motor de síntesis del propio móvil (sin enviar nada fuera). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(ctx: Context, text: String) {
        if (tts == null) {
            pending = text
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale("es", "ES"))
                    ready = true
                    val p = pending
                    pending = null
                    if (p != null) say(p)
                }
            }
        } else if (ready) {
            say(text)
        } else {
            pending = text
        }
    }

    private fun say(t: String) {
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "nv")
    }

    fun stop() {
        tts?.stop()
    }
}
