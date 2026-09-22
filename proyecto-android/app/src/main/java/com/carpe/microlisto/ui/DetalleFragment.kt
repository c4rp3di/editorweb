package com.carpe.microlisto.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.data.Segmento
import com.carpe.microlisto.databinding.FragmentDetalleBinding
import com.carpe.microlisto.debug.DebugLog
import com.carpe.microlisto.exportar.Exportador
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetalleFragment : Fragment() {

    private var _binding: FragmentDetalleBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: BaseDatos

    private var conversacionActual: Conversacion? = null
    private var segmentosActuales: List<Segmento> = emptyList()

    private var reproductor: MediaPlayer? = null
    private var botonPlayPausa: Button? = null

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
            conversacionActual = conv
            segmentosActuales = segmentos
            pintar(conv, segmentos)
        }
    }

    private fun pintar(conv: Conversacion, segmentos: List<Segmento>) {
        val formatoFecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        binding.textoTituloDetalle.text = "Conversación del ${formatoFecha.format(Date(conv.fechaMs))}"
        binding.textoResumenCabecera.text =
            "Duración: ${formatearDuracion(conv.duracionMs)} · Hablantes: ${conv.numHablantes}"

        // ===== Pestaña Transcripción con burbujas =====
        val contenedorTranscripcion = binding.contenedorTranscripcion
        contenedorTranscripcion.removeAllViews()

        // Botón compartir arriba
        val botonCompartir = Button(requireContext()).apply {
            text = "📤 Compartir como TXT"
            textSize = 12f
        }
        botonCompartir.setOnClickListener {
            val ok = Exportador.compartirTxt(requireContext(), conv, segmentos)
            if (!ok) {
                Toast.makeText(requireContext(), "No se pudo generar el archivo", Toast.LENGTH_SHORT).show()
            }
        }
        contenedorTranscripcion.addView(botonCompartir)

        val conTexto = segmentos.filter { it.texto.isNotBlank() }
        if (conTexto.isEmpty()) {
            val vacio = TextView(requireContext()).apply {
                text = if (conv.transcripcion.isBlank()) "Sin transcripción."
                       else conv.transcripcion
                setTextColor(Color.parseColor("#F0EEF8"))
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(0, 8, 0, 8)
            }
            contenedorTranscripcion.addView(vacio)
        } else {
            for (s in conTexto) {
                contenedorTranscripcion.addView(crearBurbuja(s))
            }
        }

        // ===== Pestaña Métricas =====
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
                    text = "Hablante ${hablante + 1}: ${porcentaje}% del tiempo (${formatearDuracion(tiempo)}, $turnos turnos)"
                    setTextColor(Color.parseColor("#F0EEF8"))
                    textSize = 14f
                }
                fila.addView(titulo)

                val barraContenedor = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 6, 0, 0)
                }
                val barra = View(requireContext()).apply {
                    setBackgroundColor(colorPorHablante(hablante))
                    layoutParams = LinearLayout.LayoutParams(0, 24, porcentaje.toFloat() / 100f)
                }
                barraContenedor.addView(barra)
                val relleno = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 24, 1f - porcentaje.toFloat() / 100f)
                }
                barraContenedor.addView(relleno)
                fila.addView(barraContenedor)
                contenedorMetricas.addView(fila)
            }

            val resumenTexto = TextView(requireContext()).apply {
                val sb = StringBuilder()
                sb.append("Resumen:\n")
                sb.append("• Duración total: ${formatearDuracion(conv.duracionMs)}\n")
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

        // ===== Pestaña Resumen =====
        val contenedorResumen = binding.contenedorResumen
        contenedorResumen.removeAllViews()
        val textoResumen = TextView(requireContext()).apply {
            text = if (conv.resumen.isBlank()) "Sin resumen guardado." else conv.resumen
            setTextColor(Color.parseColor("#F0EEF8"))
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        contenedorResumen.addView(textoResumen)

        // ===== Pestaña Audio con reproductor =====
        val contenedorAudio = binding.contenedorAudio
        contenedorAudio.removeAllViews()

        val archivoWav = File(conv.rutaAudio)
        if (!archivoWav.exists()) {
            val vacio = TextView(requireContext()).apply {
                text = "El archivo de audio ya no está disponible."
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 13f
            }
            contenedorAudio.addView(vacio)
        } else {
            val info = TextView(requireContext()).apply {
                text = "Archivo: ${archivoWav.name}\nTamaño: ${formatearTamano(archivoWav.length())}"
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 13f
                setPadding(0, 0, 0, 12)
            }
            contenedorAudio.addView(info)

            val boton = Button(requireContext()).apply {
                text = "▶ Reproducir"
                textSize = 14f
            }
            botonPlayPausa = boton
            boton.setOnClickListener { alternarReproduccion(archivoWav) }
            contenedorAudio.addView(boton)
        }

        mostrarTab(0)
    }

    private fun alternarReproduccion(archivo: File) {
        val rep = reproductor
        if (rep != null && rep.isPlaying) {
            rep.pause()
            botonPlayPausa?.text = "▶ Reproducir"
            return
        }
        if (rep != null) {
            rep.start()
            botonPlayPausa?.text = "⏸ Pausar"
            return
        }
        // Crear uno nuevo
        try {
            val nuevo = MediaPlayer().apply {
                setDataSource(archivo.absolutePath)
                setOnCompletionListener {
                    botonPlayPausa?.text = "▶ Reproducir"
                }
                prepare()
                start()
            }
            reproductor = nuevo
            botonPlayPausa?.text = "⏸ Pausar"
        } catch (e: Exception) {
            DebugLog.error("Detalle", "Error al reproducir: ${e.message}")
            Toast.makeText(requireContext(), "No se pudo reproducir el audio", Toast.LENGTH_SHORT).show()
        }
    }

    private fun liberarReproductor() {
        try {
            reproductor?.stop()
            reproductor?.release()
        } catch (_: Exception) {}
        reproductor = null
        botonPlayPausa = null
    }

    private fun crearBurbuja(seg: Segmento): View {
        val contexto = requireContext()
        val color = colorPorHablante(seg.hablanteId)

        val externo = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        val etiqueta = TextView(contexto).apply {
            text = "Hablante ${seg.hablanteId + 1} · ${formatearMs(seg.inicioMs)}"
            textSize = 11f
            setTextColor(color)
        }
        externo.addView(etiqueta)

        val burbuja = TextView(contexto).apply {
            text = seg.texto
            textSize = 14f
            setTextColor(Color.parseColor("#F0EEF8"))
            setPadding(24, 16, 24, 16)
            val fondo = GradientDrawable().apply {
                setColor(oscurecer(color, 0.55f))
                cornerRadius = 24f
            }
            background = fondo
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        externo.addView(burbuja)

        return externo
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

    private fun oscurecer(color: Int, factor: Float): Int {
        val r = ((color shr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((color shr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun formatearMs(ms: Long): String {
        val totalSeg = ms / 1000
        val min = totalSeg / 60
        val seg = totalSeg % 60
        return String.format("%d:%02d", min, seg)
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
        liberarReproductor()
        _binding = null
    }
}