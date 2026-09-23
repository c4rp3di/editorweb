package com.carpe.microlisto.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.microlisto.analisis.AnalizadorConversacion
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.data.Segmento
import com.carpe.microlisto.databinding.FragmentDetalleBinding
import com.carpe.microlisto.debug.DebugLog
import com.carpe.microlisto.exportar.Exportador
import com.carpe.microlisto.reprocesado.AjustesReproceso
import com.carpe.microlisto.reprocesado.Reprocesador
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

    private var radioReprocesar: RadioGroup? = null
    private var inputUmbral: EditText? = null
    private var inputMinFrag: EditText? = null
    private var inputGap: EditText? = null
    private var inputHop: EditText? = null
    private var botonReprocesar: Button? = null
    private var textoEstadoReproceso: TextView? = null

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
            if (conv == null) { mostrarSinSeleccion(); return@launch }
            conversacionActual = conv
            segmentosActuales = segmentos
            pintar(conv, segmentos)
        }
    }

    private fun pintar(conv: Conversacion, segmentos: List<Segmento>) {
        val formatoFecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        binding.textoTituloDetalle.text = conv.titulo.ifBlank { "Conversación del ${formatoFecha.format(Date(conv.fechaMs))}" }
        binding.textoResumenCabecera.text =
            "${formatoFecha.format(Date(conv.fechaMs))} · ${formatearDuracion(conv.duracionMs)} · ${conv.numHablantes} hablantes"

        // ==== Transcripción ====
        val contT = binding.contenedorTranscripcion
        contT.removeAllViews()

        val filaAcciones = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val botonCompartir = Button(requireContext()).apply {
            text = "📤 TXT"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        botonCompartir.setOnClickListener {
            if (!Exportador.compartirTxt(requireContext(), conv, segmentos)) {
                Toast.makeText(requireContext(), "No se pudo generar", Toast.LENGTH_SHORT).show()
            }
        }
        filaAcciones.addView(botonCompartir)

        val botonJson = Button(requireContext()).apply {
            text = "📤 JSON"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        botonJson.setOnClickListener {
            if (!Exportador.compartirJson(requireContext(), conv, segmentos)) {
                Toast.makeText(requireContext(), "No se pudo generar", Toast.LENGTH_SHORT).show()
            }
        }
        filaAcciones.addView(botonJson)

        val botonCopiar = Button(requireContext()).apply {
            text = "📋 Copiar"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        botonCopiar.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("transcripcion", conv.transcripcion))
            Toast.makeText(requireContext(), "Transcripción copiada", Toast.LENGTH_SHORT).show()
        }
        filaAcciones.addView(botonCopiar)

        val botonRenombrar = Button(requireContext()).apply {
            text = "✏️"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
        }
        botonRenombrar.setOnClickListener { mostrarDialogoRenombrar(conv) }
        filaAcciones.addView(botonRenombrar)

        contT.addView(filaAcciones)

        val conTexto = segmentos.filter { it.texto.isNotBlank() }
        val texto = TextView(requireContext()).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 8)
            setLineSpacing(6f, 1.15f)
        }

        if (conTexto.isEmpty()) {
            texto.text = if (conv.transcripcion.isBlank()) "Sin transcripción." else conv.transcripcion
            texto.setTextColor(Color.parseColor("#F0EEF8"))
        } else {
            val sb = SpannableStringBuilder()
            for (s in conTexto) {
                val color = colorPorHablante(s.hablanteId)
                val ini = sb.length
                sb.append(s.texto)
                sb.append(" ")
                val fin = sb.length
                sb.setSpan(ForegroundColorSpan(color), ini, fin, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(UnderlineSpan(), ini, fin, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            texto.text = sb
        }
        contT.addView(texto)

        val hablantesUnicos = segmentos.map { it.hablanteId }.distinct().sorted()
        if (hablantesUnicos.isNotEmpty()) {
            val leyenda = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)
            }
            for (h in hablantesUnicos) {
                val chip = TextView(requireContext()).apply {
                    text = "● H${h + 1}"
                    setTextColor(colorPorHablante(h))
                    textSize = 12f
                    setPadding(0, 0, 16, 0)
                }
                leyenda.addView(chip)
            }
            contT.addView(leyenda)
        }

        // ==== Métricas ====
        pintarPestanaMetricas(conv, segmentos)

        // ==== Resumen ====
        val contR = binding.contenedorResumen
        contR.removeAllViews()
        val txtR = TextView(requireContext()).apply {
            text = if (conv.resumen.isBlank()) {
                "Sin resumen generado. Pulsa Reprocesar en la pestaña Métricas para generarlo."
            } else {
                conv.resumen
            }
            setTextColor(Color.parseColor("#F0EEF8"))
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        contR.addView(txtR)

        // ==== Audio ====
        pintarPestanaAudio(conv)

        mostrarTab(0)
    }

    private fun pintarPestanaMetricas(conv: Conversacion, segmentos: List<Segmento>) {
        val contM = binding.contenedorMetricas
        contM.removeAllViews()

        // Bloque de reproceso
        val bloque = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }
        bloque.addView(TextView(requireContext()).apply {
            text = "🎛 Ajustes de reproceso"
            textSize = 15f
            setTextColor(Color.parseColor("#F0EEF8"))
        })
        bloque.addView(TextView(requireContext()).apply {
            text = "Cambia los valores y pulsa Reprocesar para volver a analizar este audio. También regenera el resumen."
            textSize = 11f
            setTextColor(Color.parseColor("#B0B0D0"))
            setPadding(0, 4, 0, 8)
        })

        val rg = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        val opciones = listOf("Auto" to 0, "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6)
        for ((texto, valor) in opciones) {
            val rb = RadioButton(requireContext()).apply {
                text = texto
                textSize = 11f
                tag = valor
                setTextColor(Color.parseColor("#F0EEF8"))
            }
            rg.addView(rb)
        }
        (rg.getChildAt(0) as RadioButton).isChecked = true
        radioReprocesar = rg
        bloque.addView(rg)

        fun crearCampo(etiqueta: String, valorInicial: String): EditText {
            bloque.addView(TextView(requireContext()).apply {
                text = etiqueta
                textSize = 12f
                setTextColor(Color.parseColor("#F0EEF8"))
                setPadding(0, 8, 0, 0)
            })
            val input = EditText(requireContext()).apply {
                setText(valorInicial)
                textSize = 13f
                setTextColor(Color.parseColor("#F0EEF8"))
            }
            bloque.addView(input)
            return input
        }

        inputUmbral = crearCampo("Umbral de clustering", "0.55")
        inputMinFrag = crearCampo("Duración mínima de fragmento (ms)", "2000")
        inputGap = crearCampo("Gap de fusión de turnos (ms)", "500")
        inputHop = crearCampo("Hop de ventana (ms)", "5000")

        val filaBotones = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        val botonR = Button(requireContext()).apply {
            text = "🔄 Reprocesar"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        botonR.setOnClickListener { ejecutarReproceso() }
        botonReprocesar = botonR
        filaBotones.addView(botonR)

        val botonCargar = Button(requireContext()).apply {
            text = "📥 Cargar"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        botonCargar.setOnClickListener {
            val c = requireContext()
            val numH = com.carpe.microlisto.data.AjustesDiarizacion.getNumHablantes(c)
            for (i in 0 until rg.childCount) {
                val rb = rg.getChildAt(i) as RadioButton
                if ((rb.tag as Int) == numH) rb.isChecked = true
            }
            inputUmbral?.setText(com.carpe.microlisto.data.AjustesDiarizacion.getUmbral(c).toString())
            inputMinFrag?.setText(com.carpe.microlisto.data.AjustesDiarizacion.getMinFragMs(c).toString())
            inputGap?.setText(com.carpe.microlisto.data.AjustesDiarizacion.getGapMs(c).toString())
            inputHop?.setText(com.carpe.microlisto.data.AjustesDiarizacion.getHopMs(c).toString())
            Toast.makeText(c, "Valores de Ajustes cargados", Toast.LENGTH_SHORT).show()
        }
        filaBotones.addView(botonCargar)
        bloque.addView(filaBotones)

        textoEstadoReproceso = TextView(requireContext()).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#9ad0e0"))
            setPadding(0, 8, 0, 0)
        }
        bloque.addView(textoEstadoReproceso)
        contM.addView(bloque)

        val sep = View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#34345A"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        }
        contM.addView(sep)

        if (segmentos.isEmpty()) {
            contM.addView(TextView(requireContext()).apply {
                text = "Sin datos de diarización."
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 13f
                setPadding(0, 24, 0, 0)
            })
            return
        }

        // Análisis avanzado
        val metricas = AnalizadorConversacion.analizar(segmentos, conv.duracionMs)
        val total = metricas.tiempoPorHablante.values.sum().coerceAtLeast(1L)

        contM.addView(TextView(requireContext()).apply {
            text = "Tiempo por hablante"
            textSize = 14f
            setTextColor(Color.parseColor("#F0EEF8"))
            setPadding(0, 16, 0, 8)
        })

        for ((h, t) in metricas.tiempoPorHablante.toList().sortedByDescending { it.second }) {
            val pct = (t * 100.0 / total).toInt()
            val turnos = metricas.turnosPorHablante[h] ?: 0
            val palabras = metricas.palabrasPorHablante[h] ?: 0
            val fila = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            fila.addView(TextView(requireContext()).apply {
                text = "H${h + 1}: $pct% · ${formatearDuracion(t)} · $turnos turnos · $palabras palabras"
                setTextColor(Color.parseColor("#F0EEF8"))
                textSize = 13f
            })
            val bc = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 0)
            }
            bc.addView(View(requireContext()).apply {
                setBackgroundColor(colorPorHablante(h))
                layoutParams = LinearLayout.LayoutParams(0, 20, pct.toFloat() / 100f)
            })
            bc.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 20, 1f - pct.toFloat() / 100f)
            })
            fila.addView(bc)
            contM.addView(fila)
        }

        // Interrupciones
        contM.addView(TextView(requireContext()).apply {
            text = "Interrupciones: ${metricas.interrupciones.size}"
            textSize = 14f
            setTextColor(Color.parseColor("#F0EEF8"))
            setPadding(0, 16, 0, 4)
        })
        if (metricas.interrupciones.isEmpty()) {
            contM.addView(TextView(requireContext()).apply {
                text = "Ninguna detectada."
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 12f
            })
        } else {
            val porCortador = metricas.interrupciones.groupingBy { it.hablanteQueCorta }.eachCount()
            for ((h, n) in porCortador.toList().sortedByDescending { it.second }) {
                contM.addView(TextView(requireContext()).apply {
                    text = "• H${h + 1} interrumpe $n ${if (n == 1) "vez" else "veces"}"
                    setTextColor(Color.parseColor("#B0B0D0"))
                    textSize = 12f
                })
            }
        }

        // Silencios
        if (metricas.silencios.isNotEmpty()) {
            val durTotal = metricas.silencios.sumOf { it.duracionMs }
            contM.addView(TextView(requireContext()).apply {
                text = "Silencios largos: ${metricas.silencios.size} (${formatearDuracion(durTotal)})"
                textSize = 14f
                setTextColor(Color.parseColor("#F0EEF8"))
                setPadding(0, 16, 0, 4)
            })
        }

        // Ritmo
        val totalPalabras = metricas.palabrasPorHablante.values.sum()
        val minutos = conv.duracionMs / 60000.0
        if (minutos > 0.5 && totalPalabras > 0) {
            val ppm = (totalPalabras / minutos).toInt()
            contM.addView(TextView(requireContext()).apply {
                text = "Ritmo medio: $ppm palabras/min"
                textSize = 13f
                setTextColor(Color.parseColor("#B0B0D0"))
                setPadding(0, 16, 0, 0)
            })
        }
    }

    private fun ejecutarReproceso() {
        val conv = conversacionActual ?: return
        val rg = radioReprocesar ?: return

        var numH = 0
        for (i in 0 until rg.childCount) {
            val rb = rg.getChildAt(i) as RadioButton
            if (rb.isChecked) { numH = rb.tag as Int; break }
        }
        val umbral = inputUmbral?.text?.toString()?.toFloatOrNull() ?: 0.55f
        val minFrag = inputMinFrag?.text?.toString()?.toLongOrNull() ?: 2000L
        val gap = inputGap?.text?.toString()?.toLongOrNull() ?: 500L
        val hop = inputHop?.text?.toString()?.toIntOrNull() ?: 5000

        botonReprocesar?.isEnabled = false
        textoEstadoReproceso?.text = "⏳ Procesando… (puede tardar varios minutos)"
        textoEstadoReproceso?.setTextColor(Color.parseColor("#FDCB6E"))

        viewLifecycleOwner.lifecycleScope.launch {
            val resultado = withContext(Dispatchers.Default) {
                Reprocesador.reprocesar(
                    context = requireContext().applicationContext,
                    conversacion = conv,
                    ajustes = AjustesReproceso(numH, umbral, minFrag, gap, hop),
                    db = db
                )
            }
            if (_binding == null) return@launch
            botonReprocesar?.isEnabled = true
            if (resultado.ok) {
                textoEstadoReproceso?.text = "✅ ${resultado.numHablantes} hablantes, ${resultado.numSegmentos} segmentos"
                textoEstadoReproceso?.setTextColor(Color.parseColor("#00B894"))
                val id = conversacionActual?.id ?: return@launch
                cargarConversacion(id)
            } else {
                textoEstadoReproceso?.text = "❌ ${resultado.mensaje}"
                textoEstadoReproceso?.setTextColor(Color.parseColor("#E17055"))
            }
        }
    }

    private fun mostrarDialogoRenombrar(conv: Conversacion) {
        val input = EditText(requireContext()).apply {
            setText(conv.titulo)
            setHint("Título de la conversación")
            setTextColor(Color.parseColor("#F0EEF8"))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Renombrar conversación")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val nuevo = input.text.toString().trim().ifBlank { conv.titulo }
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.actualizarTitulo(conv.id, nuevo) }
                    conversacionActual = conv.copy(titulo = nuevo)
                    binding.textoTituloDetalle.text = nuevo
                    Toast.makeText(requireContext(), "Título actualizado", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pintarPestanaAudio(conv: Conversacion) {
        val contA = binding.contenedorAudio
        contA.removeAllViews()
        val archivo = File(conv.rutaAudio)
        if (!archivo.exists()) {
            contA.addView(TextView(requireContext()).apply {
                text = "El archivo de audio ya no está disponible."
                setTextColor(Color.parseColor("#B0B0D0"))
                textSize = 13f
            })
            return
        }
        contA.addView(TextView(requireContext()).apply {
            text = "Archivo: ${archivo.name}\nTamaño: ${formatearTamano(archivo.length())}"
            setTextColor(Color.parseColor("#B0B0D0"))
            textSize = 13f
            setPadding(0, 0, 0, 12)
        })

        val fila = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        val bp = Button(requireContext()).apply {
            text = "▶ Reproducir"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        botonPlayPausa = bp
        bp.setOnClickListener { alternarReproduccion(archivo) }
        fila.addView(bp)

        val bb = Button(requireContext()).apply {
            text = "🗑 Borrar audio"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bb.setOnClickListener { confirmarBorradoAudio(conv, archivo) }
        fila.addView(bb)
        contA.addView(fila)
    }

    private fun confirmarBorradoAudio(conv: Conversacion, archivo: File) {
        AlertDialog.Builder(requireContext())
            .setTitle("Borrar audio")
            .setMessage("Se borrará el audio (${formatearTamano(archivo.length())}). La transcripción y las métricas se conservan.")
            .setPositiveButton("Borrar") { _, _ ->
                liberarReproductor()
                val ok = try { archivo.delete() } catch (_: Exception) { false }
                if (ok) {
                    Toast.makeText(requireContext(), "Audio borrado", Toast.LENGTH_SHORT).show()
                    pintarPestanaAudio(conv)
                } else {
                    Toast.makeText(requireContext(), "No se pudo borrar", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun alternarReproduccion(archivo: File) {
        val rep = reproductor
        if (rep != null && rep.isPlaying) {
            rep.pause(); botonPlayPausa?.text = "▶ Reproducir"; return
        }
        if (rep != null) {
            rep.start(); botonPlayPausa?.text = "⏸ Pausar"; return
        }
        try {
            reproductor = MediaPlayer().apply {
                setDataSource(archivo.absolutePath)
                setOnCompletionListener { botonPlayPausa?.text = "▶ Reproducir" }
                prepare()
                start()
            }
            botonPlayPausa?.text = "⏸ Pausar"
        } catch (e: Exception) {
            DebugLog.error("Detalle", "Error al reproducir: ${e.message}")
            Toast.makeText(requireContext(), "No se pudo reproducir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun liberarReproductor() {
        try { reproductor?.stop(); reproductor?.release() } catch (_: Exception) {}
        reproductor = null
        botonPlayPausa = null
    }

    private fun colorPorHablante(id: Int): Int {
        val p = intArrayOf(
            Color.parseColor("#6C5CE7"),
            Color.parseColor("#00B894"),
            Color.parseColor("#E17055"),
            Color.parseColor("#0984E3"),
            Color.parseColor("#FDCB6E"),
            Color.parseColor("#E84393")
        )
        return p[id % p.size]
    }

    private fun formatearDuracion(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val seg = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, seg)
               else String.format("%d:%02d", m, seg)
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
        binding.textoResumenCabecera.text = "Abre una conversación desde el Historial."
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