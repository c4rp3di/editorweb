package com.carpe.microlisto.ui

import android.app.AlertDialog
import android.app.ProgressDialog
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
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.microlisto.ConfiguracionInicialActivity
import com.carpe.microlisto.data.AjustesDiarizacion
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.databinding.FragmentAjustesBinding
import com.carpe.microlisto.debug.DebugLog
import com.carpe.microlisto.transcripcion.VoskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AjustesFragment : Fragment() {

    private var _binding: FragmentAjustesBinding? = null
    private val binding get() = _binding!!
    private lateinit var voskManager: VoskManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAjustesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        voskManager = VoskManager(requireContext().applicationContext)

        binding.botonAsistenteHyperos.setOnClickListener {
            startActivity(Intent(requireContext(), ConfiguracionInicialActivity::class.java))
        }
        binding.botonVerLog.setOnClickListener { mostrarDialogoLog() }
        binding.botonGuardarAjustes.setOnClickListener { guardarAjustes() }
        binding.botonResetearAjustes.setOnClickListener { resetearAjustes() }
        binding.botonLimpiarWavs.setOnClickListener { confirmarLimpiarWavs() }
        binding.botonBorrarTodo.setOnClickListener { confirmarBorrarTodo() }
        binding.botonExportarBackup.setOnClickListener {
            com.carpe.microlisto.AppLogic.exportarBackup(requireContext())
            Toast.makeText(requireContext(), "Backup exportado a /Microlisto/", Toast.LENGTH_SHORT).show()
        }
        binding.botonDescargarVosk.setOnClickListener { descargarVosk() }
        binding.botonBorrarVosk.setOnClickListener { borrarVosk() }

        cargarAjustesActuales()
        cargarEstadisticas()
        actualizarEstadoVosk()
    }

    override fun onResume() {
        super.onResume()
        cargarEstadisticas()
        actualizarEstadoVosk()
    }

    private fun cargarAjustesActuales() {
        val c = requireContext()
        val numH = AjustesDiarizacion.getNumHablantes(c)
        when (numH) {
            0 -> binding.radioHablantesAuto.isChecked = true
            1 -> binding.radioHablantes1.isChecked = true
            2 -> binding.radioHablantes2.isChecked = true
            3 -> binding.radioHablantes3.isChecked = true
            4 -> binding.radioHablantes4.isChecked = true
            5 -> binding.radioHablantes5.isChecked = true
            6 -> binding.radioHablantes6.isChecked = true
        }
        binding.inputUmbral.setText(AjustesDiarizacion.getUmbral(c).toString())
        binding.inputMinFrag.setText(AjustesDiarizacion.getMinFragMs(c).toString())
        binding.inputGap.setText(AjustesDiarizacion.getGapMs(c).toString())
        binding.inputHop.setText(AjustesDiarizacion.getHopMs(c).toString())
    }

    private fun guardarAjustes() {
        val numH = when {
            binding.radioHablantes1.isChecked -> 1
            binding.radioHablantes2.isChecked -> 2
            binding.radioHablantes3.isChecked -> 3
            binding.radioHablantes4.isChecked -> 4
            binding.radioHablantes5.isChecked -> 5
            binding.radioHablantes6.isChecked -> 6
            else -> 0
        }
        val umbral = binding.inputUmbral.text.toString().toFloatOrNull() ?: 0.55f
        val minFrag = binding.inputMinFrag.text.toString().toLongOrNull() ?: 2000L
        val gap = binding.inputGap.text.toString().toLongOrNull() ?: 500L
        val hop = binding.inputHop.text.toString().toIntOrNull() ?: 5000

        AjustesDiarizacion.guardar(requireContext(), numH, umbral, minFrag, gap, hop)
        Toast.makeText(requireContext(), "Ajustes guardados", Toast.LENGTH_SHORT).show()
    }

    private fun resetearAjustes() {
        AjustesDiarizacion.resetear(requireContext())
        cargarAjustesActuales()
        Toast.makeText(requireContext(), "Valores restablecidos", Toast.LENGTH_SHORT).show()
    }

    private fun actualizarEstadoVosk() {
        val descargado = voskManager.estaDescargado()
        if (descargado) {
            binding.textoEstadoVosk.text = "✅ Modelo Vosk cargado (español grande, ~2.5 GB)"
            binding.botonDescargarVosk.isEnabled = false
            binding.botonDescargarVosk.text = "Descargado"
            binding.botonBorrarVosk.isEnabled = true
        } else {
            binding.textoEstadoVosk.text = "❌ No descargado. Sin modelo, se graba WAV pero no se transcribe."
            binding.botonDescargarVosk.isEnabled = true
            binding.botonDescargarVosk.text = "📥 Descargar modelo (~1.4 GB)"
            binding.botonBorrarVosk.isEnabled = false
        }
    }

    private fun descargarVosk() {
        AlertDialog.Builder(requireContext())
            .setTitle("Descargar modelo Vosk")
            .setMessage("Se descargará el modelo español grande (~1.4 GB comprimido, ~2.5 GB descomprimido). " +
                    "Usa WiFi. Durante el proceso se necesitan ~4 GB libres temporalmente. " +
                    "El modelo se guarda en /Microlisto/vosk/ y no se borra al desinstalar la app.")
            .setPositiveButton("Descargar") { _, _ ->
                val activity = activity ?: return@setPositiveButton
                val pd = ProgressDialog(requireContext()).apply {
                    setTitle("Descargando Vosk…")
                    setMessage("Iniciando…")
                    setCancelable(false)
                    show()
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = voskManager.descargar { pct, texto ->
                        // El callback se ejecuta desde un hilo de fondo.
                        // Hay que saltar al hilo principal para tocar la UI.
                        activity.runOnUiThread {
                            try { pd.setMessage("$pct% — $texto") } catch (_: Exception) {}
                        }
                    }
                    try { pd.dismiss() } catch (_: Exception) {}
                    Toast.makeText(
                        requireContext(),
                        if (ok) "Modelo Vosk descargado" else "Error en la descarga",
                        Toast.LENGTH_LONG
                    ).show()
                    actualizarEstadoVosk()
                    cargarEstadisticas()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun borrarVosk() {
        AlertDialog.Builder(requireContext())
            .setTitle("Borrar modelo Vosk")
            .setMessage("Se liberarán ~2.5 GB. La app seguirá grabando WAV pero no transcribirá hasta que descargues otro modelo.")
            .setPositiveButton("Borrar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { voskManager.borrar() }
                    Toast.makeText(
                        requireContext(),
                        if (ok) "Modelo borrado" else "No se pudo borrar",
                        Toast.LENGTH_SHORT
                    ).show()
                    actualizarEstadoVosk()
                    cargarEstadisticas()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun cargarEstadisticas() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = BaseDatos(requireContext())
            val stats = withContext(Dispatchers.IO) { db.obtenerEstadisticas() }
            if (_binding == null) return@launch
            binding.textoStats.text =
                "Conversaciones: ${stats.numConversaciones}\n" +
                "Tiempo total: ${formatearDuracion(stats.duracionTotalMs)}\n" +
                "Espacio WAVs: ${formatearTamano(stats.espacioWavsBytes)}"
        }
    }

    private fun confirmarLimpiarWavs() {
        AlertDialog.Builder(requireContext())
            .setTitle("Limpiar audios")
            .setMessage("Se borrarán todos los archivos WAV. Las transcripciones se conservan.")
            .setPositiveButton("Borrar audios") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val db = BaseDatos(requireContext())
                    val rutas = withContext(Dispatchers.IO) { db.obtenerTodasLasRutasAudio() }
                    var borrados = 0
                    withContext(Dispatchers.IO) {
                        for (r in rutas) {
                            try {
                                val f = File(r)
                                if (f.exists() && f.delete()) borrados++
                            } catch (_: Exception) {}
                        }
                    }
                    cargarEstadisticas()
                    Toast.makeText(requireContext(), "Se borraron $borrados archivos", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarBorrarTodo() {
        AlertDialog.Builder(requireContext())
            .setTitle("Borrar todo")
            .setMessage("Se borrarán TODAS las conversaciones, transcripciones y audios.")
            .setPositiveButton("Borrar todo") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val db = BaseDatos(requireContext())
                    withContext(Dispatchers.IO) {
                        val rutas = db.obtenerTodasLasRutasAudio()
                        for (r in rutas) {
                            try {
                                val f = File(r)
                                if (f.exists()) f.delete()
                            } catch (_: Exception) {}
                        }
                        db.eliminarTodasLasConversaciones()
                    }
                    cargarEstadisticas()
                    Toast.makeText(requireContext(), "Todo borrado", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoLog() {
        val contexto = requireContext()
        val layout = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        layout.addView(TextView(contexto).apply { text = "Log de depuración"; textSize = 18f })
        val textView = TextView(contexto).apply {
            text = DebugLog.obtenerTodo()
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(8, 8, 8, 8)
        }
        layout.addView(ScrollView(contexto).apply {
            addView(textView)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
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

        val dialogo = AlertDialog.Builder(contexto).setView(layout).setCancelable(true).create()
        botonCopiar.setOnClickListener {
            val cm = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("microlisto_log", textView.text))
            Toast.makeText(contexto, "Log copiado", Toast.LENGTH_SHORT).show()
        }
        botonLimpiar.setOnClickListener {
            DebugLog.limpiar()
            textView.text = DebugLog.obtenerTodo()
            Toast.makeText(contexto, "Log limpiado", Toast.LENGTH_SHORT).show()
        }
        botonCerrar.setOnClickListener { dialogo.dismiss() }
        dialogo.show()
    }

    private fun formatearDuracion(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val seg = s % 60
        return if (h > 0) String.format("%d h %d min", h, m)
               else if (m > 0) String.format("%d min %d s", m, seg)
               else String.format("%d s", seg)
    }

    private fun formatearTamano(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}