package com.carpe.microlisto.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.carpe.microlisto.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistorialFragment : Fragment() {

    private var _binding: FragmentHistorialBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: BaseDatos
    private var textoBusqueda: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistorialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = BaseDatos(requireContext())

        binding.campoBusqueda.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                textoBusqueda = s?.toString() ?: ""
                cargarConversaciones()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        cargarConversaciones()
    }

    override fun onResume() {
        super.onResume()
        cargarConversaciones()
    }

    private fun cargarConversaciones() {
        viewLifecycleOwner.lifecycleScope.launch {
            val lista = withContext(Dispatchers.IO) {
                if (textoBusqueda.isBlank()) db.listarConversaciones()
                else db.buscarConversaciones(textoBusqueda)
            }
            if (_binding == null) return@launch
            pintarLista(lista)
        }
    }

    private fun pintarLista(lista: List<Conversacion>) {
        val contenedor = binding.contenedorConversaciones
        contenedor.removeAllViews()

        if (lista.isEmpty()) {
            val vacio = TextView(requireContext()).apply {
                text = if (textoBusqueda.isBlank()) "Todavía no hay conversaciones grabadas."
                       else "Ninguna conversación coincide con \"$textoBusqueda\"."
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
                text = "${formatoFecha.format(Date(c.fechaMs))} · ${formatearDuracion(c.duracionMs)} · ${c.numHablantes} hablantes"
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
            botonAbrir.setOnClickListener { abrirDetalle(c.id) }
            botones.addView(botonAbrir)

            val botonBorrar = Button(requireContext()).apply {
                text = "Borrar"
                textSize = 12f
            }
            botonBorrar.setOnClickListener { confirmarBorrado(c) }
            botones.addView(botonBorrar)

            tarjeta.addView(botones)

            tarjeta.setOnClickListener { abrirDetalle(c.id) }

            contenedor.addView(tarjeta)
        }
    }

    private fun confirmarBorrado(c: Conversacion) {
        val formatoFecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val tamanoWav = try {
            val f = File(c.rutaAudio)
            if (f.exists()) " El archivo de audio ocupa ${formatearTamano(f.length())}." else ""
        } catch (_: Exception) { "" }

        AlertDialog.Builder(requireContext())
            .setTitle("Borrar conversación")
            .setMessage("¿Borrar la conversación del ${formatoFecha.format(Date(c.fechaMs))}?$tamanoWav Esta acción no se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ -> borrarConversacion(c) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun borrarConversacion(c: Conversacion) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Primero borrar el archivo de audio del almacenamiento
                try {
                    val archivo = File(c.rutaAudio)
                    if (archivo.exists()) {
                        val borrado = archivo.delete()
                        DebugLog.info("Historial", "WAV borrado: ${archivo.name} = $borrado")
                    }
                } catch (e: Exception) {
                    DebugLog.warn("Historial", "No se pudo borrar WAV: ${e.message}")
                }
                // Luego la fila de la base de datos (con CASCADE borra los segmentos)
                db.eliminarConversacion(c.id)
            }
            cargarConversaciones()
        }
    }

    private fun abrirDetalle(idConversacion: Long) {
        val activity = activity as? MainActivity ?: return
        activity.abrirDetalle(idConversacion)
    }

    private fun formatearDuracion(ms: Long): String {
        val totalSeg = ms / 1000
        val horas = totalSeg / 3600
        val minutos = (totalSeg % 3600) / 60
        val segundos = totalSeg % 60
        return if (horas > 0) String.format("%d:%02d:%02d", horas, minutos, segundos)
               else String.format("%d:%02d", minutos, segundos)
    }

    private fun formatearTamano(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}