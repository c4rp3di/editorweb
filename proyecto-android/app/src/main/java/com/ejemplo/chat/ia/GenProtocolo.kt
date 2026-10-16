package com.ejemplo.chat.ia

import android.os.Bundle

object GenProtocolo {
    const val MSG_INICIAR = 1
    const val MSG_PID = 2
    const val MSG_PROGRESO = 3
    const val MSG_FIN = 4

    fun imagen(modelPath: String, prompt: String, negativePrompt: String, outputPath: String,
              width: Int, height: Int, steps: Int): Bundle =
        Bundle().apply {
            putString("tipo", "imagen")
            putString("modelPath", modelPath)
            putString("prompt", prompt)
            putString("negativePrompt", negativePrompt)
            putString("outputPath", outputPath)
            putInt("width", width)
            putInt("height", height)
            putInt("steps", steps)
        }
}
