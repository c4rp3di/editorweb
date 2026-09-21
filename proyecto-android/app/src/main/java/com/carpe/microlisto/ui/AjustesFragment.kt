package com.carpe.microlisto.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.carpe.microlisto.ConfiguracionInicialActivity
import com.carpe.microlisto.databinding.FragmentAjustesBinding
import com.carpe.microlisto.debug.DebugLog

class AjustesFragment : Fragment() {

    private var _binding: FragmentAjustesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAjustesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.botonAsistenteHyperos.setOnClickListener {
            startActivity(Intent(requireContext(), ConfiguracionInicialActivity::class.java))
        }

        binding.botonVerLog.setOnClickListener {
            mostrarDialogoLog()
        }
    }

    private fun mostrarDialogoLog() {
        val contexto = requireContext()

        // Contenedor con TextView scrollable y botones
        val layout = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val titulo = TextView(contexto).apply {
            text = "Log de depuración"
            textSize = 18f
        }
        layout.addView(titulo)

        val textView = TextView(contexto).apply {
            text = DebugLog.obtenerTodo()
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(8, 8, 8, 8)
        }

        val scroll = ScrollView(contexto).apply {
            addView(textView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        layout.addView(scroll)

        val botones = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        val botonCopiar = Button(contexto).apply { text = "📋 Copiar" }
        val botonLimpiar = Button(contexto).apply { text = "🧹 Limpiar" }
        val botonCerrar = Button(contexto).apply { text = "Cerrar" }

        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        botones.addView(botonCopiar, lp)
        botones.addView(botonLimpiar, lp)
        botones.addView(botonCerrar, lp)
        layout.addView(botones)

        val dialogo = AlertDialog.Builder(contexto)
            .setView(layout)
            .setCancelable(true)
            .create()

        botonCopiar.setOnClickListener {
            val cm = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("microlisto_log", textView.text))
            Toast.makeText(contexto, "Log copiado al portapapeles", Toast.LENGTH_SHORT).show()
        }

        botonLimpiar.setOnClickListener {
            DebugLog.limpiar()
            textView.text = DebugLog.obtenerTodo()
            Toast.makeText(contexto, "Log limpiado", Toast.LENGTH_SHORT).show()
        }

        botonCerrar.setOnClickListener { dialogo.dismiss() }

        dialogo.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}