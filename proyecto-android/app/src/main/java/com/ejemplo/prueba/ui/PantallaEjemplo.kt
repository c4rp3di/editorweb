package com.ejemplo.prueba.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.ejemplo.prueba.R

// Pantalla de ejemplo. Duplica este archivo (y su layout) para cada pantalla nueva.
class PantallaEjemplo : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estadoGuardado: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_pantalla_ejemplo, contenedor, false)
    }
}
