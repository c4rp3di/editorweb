package com.ejemplo.prueba

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.ejemplo.prueba.ui.PantallaEjemplo
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
                if (savedInstanceState == null) mostrarPantalla(PantallaEjemplo())
                vibrar(500)
// [ONCREATE_FIN:FUNCIONALIDADES]
    }

        // Funcionalidad: varias-pantallas
    private fun mostrarPantalla(pantalla: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, pantalla)
            .commit()
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
