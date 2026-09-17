package com.carpe.gestorarchivos.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.GestorArchivos

class PantallaEditorTexto : Fragment() {

    private lateinit var editor: EditText
    private lateinit var nombre: TextView
    private var uri: Uri? = null
    private var nombreArchivo: String = "archivo.txt"
    private var contenidoOriginal: String = ""
    private var hayCambios = false

    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?
    ): View {
        val vista = inflater.inflate(R.layout.dialog_editor_texto, contenedor, false)
        editor = vista.findViewById(R.id.editorTexto)
        nombre = vista.findViewById(R.id.nombreEditor)

        uri = arguments?.getString("uri")?.let { Uri.parse(it) }
        nombreArchivo = arguments?.getString("nombre") ?: "archivo.txt"
        contenidoOriginal = arguments?.getString("contenido") ?: ""
        nombre.text = nombreArchivo
        editor.setText(contenidoOriginal)

        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                hayCambios = s?.toString() != contenidoOriginal
                nombre.text = if (hayCambios) "• $nombreArchivo" else nombreArchivo
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        vista.findViewById<View>(R.id.botonVolver).setOnClickListener { salir() }
        vista.findViewById<View>(R.id.botonGuardarTexto).setOnClickListener { guardar() }

        return vista
    }

    private fun guardar() {
        val u = uri ?: return
        val doc = DocumentFile.fromSingleUri(requireContext(), u) ?: return
        val ok = GestorArchivos.escribirTexto(requireContext(), doc, editor.text.toString())
        if (ok) {
            contenidoOriginal = editor.text.toString()
            hayCambios = false
            nombre.text = nombreArchivo
            Toast.makeText(requireContext(), "Guardado", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "No se pudo guardar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun salir() {
        if (!hayCambios) { parentFragmentManager.popBackStack(); return }
        AlertDialog.Builder(requireContext())
            .setTitle("Cambios sin guardar")
            .setMessage("¿Salir sin guardar?")
            .setPositiveButton("Salir sin guardar") { _, _ -> parentFragmentManager.popBackStack() }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Guardar y salir") { _, _ -> guardar(); parentFragmentManager.popBackStack() }
            .show()
    }

    companion object {
        fun nueva(uri: Uri, nombre: String, contenido: String): PantallaEditorTexto {
            val f = PantallaEditorTexto()
            f.arguments = Bundle().apply {
                putString("uri", uri.toString())
                putString("nombre", nombre)
                putString("contenido", contenido)
            }
            return f
        }
    }
}