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
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.data.Segmento
import com.carpe.microlisto.databinding.FragmentDetalleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetalleFragment : Fragment() {

    private var _binding: FragmentDetalleBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: BaseDatos

    companion object {
        const val ARG_ID_CONVERSACION = "id_conversacion"

        fun nuevo(idConversacion: Long): DetalleFragment {
            val frag = DetalleFragment()
            val args = Bundle()
            args.putLong(ARG_ID_CONVERSACION, idConversacion)
            frag.arguments = args
            return frag
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetalleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = BaseDatos(requireContext())

        val id = arguments?.getLong(ARG_ID_CONVERSACION, -1L) ?: -1L
        if (id <= 0) {
            mostrarSinSeleccion()
            return
        }

        // Pestañas: 4 botones que muestran/ocultan el contenedor correspondiente
        binding.botonTabTranscripcion.setOnClickListener { mostrarTab(0) }
        binding.botonTabMetricas.setOnClickListener { mostrarTab(1) }
        binding.botonTabResumen.setOnClickListener { mostrarTab(2) }
        binding.botonTabAudio.setOnClickListener { mostrarTab(3) }

        cargarConversacion(id)
    }

    private fun cargarConversacion(id: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val conv = withContext(Dispatchers.IO) { db.obtenerConversacion(id) }
            val segmentos = withContext(Dispatchers.IO) { db.listarSegmentos(id) }
            if (_binding == null) return@launch
            if (conv == null) {
                mostrarSinSeleccion()
                return@launch
            }
            pintar(conv, segmentos)
        }
    }

    private fun pintar(conv: Conversacion, segmentos: List<Segmento>) {
        val formatoFecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        binding.textoTituloDetalle.text = "Conversación del ${formatoFecha.format(Date(conv.fechaMs))}"
        binding.textoResumenCabecera.text =
            "Duración: ${conv.duracionMs / 1000}s · Hablantes: ${conv.numHablantes}"

        // Pestaña Transcripción
        val contenedorTranscripcion = binding.contenedorTranscripcion
        contenedorTranscripcion.removeAllViews()
        if (conv.transcripcion.isBlank()) {
            val vacio = TextView(requireContext()).apply {
                text = "Sin transcripción."
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 13f
                setPadding(0, 24, 0, 0)
            }
            contenedorTranscripcion.addView(vacio)
        } else {
            val texto = TextView(requireContext()).apply {
                text = conv.transcripcion
                setTextColor(Color.parseColor("#F0EEF8"))
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(0, 8, 0, 8)
            }
            contenedorTranscripcion.addView(texto)
        }

        // Pestaña Métricas
        val contenedorMetricas = binding.contenedorMetricas
        contenedorMetricas.removeAllViews()
        if (segmentos.isEmpty()) {
            val vacio = TextView(requireContext()).apply {
                text = "Sin datos de diarización."
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 13f
                setPadding(0, 24, 0, 0)
            }
            contenedorMetricas.addView(vacio)
        } else {
            // Agrupar por hablante
            val tiempoPorHablante = mutableMapOf<Int, Long>()
            val turnosPorHablante = mutableMapOf<Int, Int>()
            for (s in segmentos) {
                val dur = s.finMs - s.inicioMs
                tiempoPorHablante[s.hablanteId] = (tiempoPorHablante[s.hablanteId] ?: 0L) + dur
                turnosPorHablante[s.hablanteId] = (turnosPorHablante[s.hablanteId] ?: 0) + 1
            }

            val total = tiempoPorHablante.values.sum().coerceAtLeast(1L)

            for ((hablante, tiempo) in tiempoPorHablante.toList().sortedByDescending { it.second }) {
                val porcentaje = (tiempo * 100.0 / total).toInt()
                val turnos = turnosPorHablante[hablante] ?: 0

                val fila = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 12, 0, 12)
                }

                val titulo = TextView(requireContext()).apply {
                    text = "Hablante ${hablante + 1}: ${porcentaje}% del tiempo (${tiempo / 1000}s, $turnos turnos)"
                    setTextColor(Color.parseColor("#F0EEF8"))
                    textSize = 14f
                }
                fila.addView(titulo)

                // Barra horizontal simple con un View con fondo de color
                val barraContenedor = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 6, 0, 0)
                }
                val barra = View(requireContext()).apply {
                    setBackgroundColor(colorPorHablante(hablante))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        24,
                        porcentaje.toFloat() / 100f
                    )
                }
                barraContenedor.addView(barra)
                // Relleno invisible para llegar al 100%
                val relleno = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        24,
                        1f - porcentaje.toFloat() / 100f
                    )
                }
                barraContenedor.addView(relleno)
                fila.addView(barraContenedor)

                contenedorMetricas.addView(fila)
            }

            // Resumen textual
            val resumenTexto = TextView(requireContext()).apply {
                val sb = StringBuilder()
                sb.append("Resumen:\n")
                sb.append("• Duración total: ${conv.duracionMs / 1000}s\n")
                sb.append("• Hablantes detectados: ${tiempoPorHablante.size}\n")
                val masHablador = tiempoPorHablante.maxByOrNull { it.value }
                if (masHablador != null) {
                    val pct = (masHablador.value * 100.0 / total).toInt()
                    sb.append("• Hablante más activo: #${masHablador.key + 1} ($pct%)\n")
                }
                val totalTurnos = turnosPorHablante.values.sum()
                sb.append("• Turnos totales: $totalTurnos\n")
                text = sb.toString()
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 13f
                setPadding(0, 24, 0, 0)
            }
            contenedorMetricas.addView(resumenTexto)
        }

        // Pestaña Resumen (texto plano del resumen guardado)
        val contenedorResumen = binding.contenedorResumen
        contenedorResumen.removeAllViews()
        val textoResumen = TextView(requireContext()).apply {
            text = if (conv.resumen.isBlank()) "Sin resumen guardado." else conv.resumen
            setTextColor(Color.parseColor("#F0EEF8"))
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        contenedorResumen.addView(textoResumen)

        // Pestaña Audio
        val contenedorAudio = binding.contenedorAudio
        contenedorAudio.removeAllViews()
        val infoAudio = TextView(requireContext()).apply {
            text = "Archivo: ${conv.rutaAudio}\n\n(Reproducción: pendiente de implementar en el Bloque 6)"
            setTextColor(Color.parseColor("#B0B0D0"))
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        contenedorAudio.addView(infoAudio)

        mostrarTab(0)
    }

    private fun colorPorHablante(id: Int): Int {
        val paleta = intArrayOf(
            Color.parseColor("#6C5CE7"),
            Color.parseColor("#00B894"),
            Color.parseColor("#E17055"),
            Color.parseColor("#0984E3"),
            Color.parseColor("#FDCB6E"),
            Color.parseColor("#E84393")
        )
        return paleta[id % paleta.size]
    }

    private fun mostrarTab(indice: Int) {
        binding.contenedorTranscripcion.visibility = if (indice == 0) View.VISIBLE else View.GONE
        binding.contenedorMetricas.visibility = if (indice == 1) View.VISIBLE else View.GONE
        binding.contenedorResumen.visibility = if (indice == 2) View.VISIBLE else View.GONE
        binding.contenedorAudio.visibility = if (indice == 3) View.VISIBLE else View.GONE
    }

    private fun mostrarSinSeleccion() {
        binding.textoTituloDetalle.text = "Sin conversación seleccionada"
        binding.textoResumenCabecera.text = "Abre una conversación desde el Historial para ver su detalle."
        binding.contenedorTranscripcion.removeAllViews()
        binding.contenedorMetricas.removeAllViews()
        binding.contenedorResumen.removeAllViews()
        binding.contenedorAudio.removeAllViews()
        mostrarTab(0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}