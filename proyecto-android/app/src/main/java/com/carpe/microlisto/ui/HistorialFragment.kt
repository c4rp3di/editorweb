package com.carpe.microlisto.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.microlisto.MainActivity
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.databinding.FragmentHistorialBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistorialFragment : Fragment() {

    private var _binding: FragmentHistorialBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: BaseDatos

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistorialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = BaseDatos(requireContext())
        cargarConversaciones()
    }

    private fun cargarConversaciones() {
        viewLifecycleOwner.lifecycleScope.launch {
            val lista = withContext(Dispatchers.IO) { db.listarConversaciones() }
            if (_binding == null) return@launch
            pintarLista(lista)
        }
    }

    private fun pintarLista(lista: List<Conversacion>) {
        val contenedor = binding.contenedorConversaciones
        contenedor.removeAllViews()

        if (lista.isEmpty()) {
            val vacio = TextView(requireContext()).apply {
                text = "Todavía no hay conversaciones grabadas."
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 64, 0, 0)
            }
            contenedor.addView(vacio)
            return
        }

        val formatoFecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        for (c in lista) {
            val tarjeta = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(Color.parseColor("#20203A"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }
                isClickable = true
                isFocusable = true
            }

            val cabecera = TextView(requireContext()).apply {
                text = "${formatoFecha.format(Date(c.fechaMs))} · ${c.duracionMs / 1000}s · ${c.numHablantes} hablantes"
                setTextColor(Color.parseColor("#F0EEF8"))
                textSize = 13f
            }
            tarjeta.addView(cabecera)

            val preview = TextView(requireContext()).apply {
                text = if (c.transcripcion.length > 180) {
                    c.transcripcion.take(180) + "…"
                } else {
                    c.transcripcion
                }
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 12f
                setPadding(0, 8, 0, 0)
            }
            tarjeta.addView(preview)

            val botones = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 0)
            }

            val botonAbrir = Button(requireContext()).apply {
                text = "Abrir"
                textSize = 12f
            }
            botonAbrir.setOnClickListener {
                abrirDetalle(c.id)
            }
            botones.addView(botonAbrir)

            val botonBorrar = Button(requireContext()).apply {
                text = "Borrar"
                textSize = 12f
            }
            botonBorrar.setOnClickListener {
                borrarConversacion(c.id)
            }
            botones.addView(botonBorrar)

            tarjeta.addView(botones)

            // Pulsar la tarjeta entera también abre el detalle
            tarjeta.setOnClickListener {
                abrirDetalle(c.id)
            }

            contenedor.addView(tarjeta)
        }
    }

    private fun abrirDetalle(idConversacion: Long) {
        val activity = activity as? MainActivity ?: return
        activity.abrirDetalle(idConversacion)
    }

    private fun borrarConversacion(id: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.eliminarConversacion(id) }
            cargarConversaciones()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}