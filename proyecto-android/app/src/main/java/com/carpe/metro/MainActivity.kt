package com.carpe.metro

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.carpe.metro.AppLogic
import androidx.fragment.app.Fragment
import com.carpe.metro.ui.PantallaWeb
// [IMPORTS:FUNCIONALIDADES]

class MainActivity : AppCompatActivity() {

    // [PROPIEDADES:FUNCIONALIDADES]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [ONCREATE:FUNCIONALIDADES]
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) mostrarPantallaWebFragment()
        // [ONCREATE_FIN:FUNCIONALIDADES]
        AppLogic.onIniciar(this)
    }

    // Funcionalidad: varias-pantallas
    private fun mostrarPantalla(pantalla: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, pantalla)
            .commit()
    }

    // Funcionalidad: pantalla-web
    private fun mostrarPantallaWebFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, PantallaWeb())
            .commit()
    }

    // [METODOS:FUNCIONALIDADES]
}
