package com.ejemplo.textfuncionalidades.ui

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ejemplo.textfuncionalidades.R

// Formulario de ejemplo con validación básica. Añádelo con
// mostrarPantalla(PantallaFormulario()) si tienes varias-pantallas activo,
// o instáncialo donde te haga falta.
class PantallaFormulario : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estadoGuardado: Bundle?
    ): View {
        val vista = inflater.inflate(R.layout.fragment_formulario, contenedor, false)
        val campoNombre = vista.findViewById<EditText>(R.id.campoNombre)
        val campoEmail = vista.findViewById<EditText>(R.id.campoEmail)
        vista.findViewById<Button>(R.id.botonEnviar).setOnClickListener {
            val nombre = campoNombre.text.toString().trim()
            val email = campoEmail.text.toString().trim()
            if (nombre.isEmpty()) {
                campoNombre.error = "Este campo es obligatorio"
                return@setOnClickListener
            }
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                campoEmail.error = "Correo no válido"
                return@setOnClickListener
            }
            Toast.makeText(requireContext(), "Formulario válido: $nombre", Toast.LENGTH_SHORT).show()
        }
        return vista
    }
}
