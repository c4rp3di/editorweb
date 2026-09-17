package com.ejemplo.text

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
// [IMPORTS:FUNCIONALIDADES]

class MainActivity : AppCompatActivity() {

    // [PROPIEDADES:FUNCIONALIDADES]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [ONCREATE:FUNCIONALIDADES]
        setContentView(R.layout.activity_main)
        // [ONCREATE_FIN:FUNCIONALIDADES]
    }

        // Funcionalidad: vibracion
    private fun vibrar(duracionMs: Long = 200) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duracionMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duracionMs)
        }
    }

// [METODOS:FUNCIONALIDADES]
}
