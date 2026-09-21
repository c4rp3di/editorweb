package com.carpe.microlisto.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.microlisto.MicrolistoService
import com.carpe.microlisto.R
import com.carpe.microlisto.databinding.FragmentGrabarBinding
import kotlinx.coroutines.launch

class GrabarFragment : Fragment() {

    private var _binding: FragmentGrabarBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGrabarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.botonGrabar.setOnClickListener {
            if (MicrolistoService.estadoGrabacion.value.grabando) {
                MicrolistoService.parar(requireContext())
            } else {
                if (tienePermisoAudio()) {
                    MicrolistoService.iniciar(requireContext())
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.requiere_permisos_obligatorios,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        observarEstado()
    }

    private fun observarEstado() {
        viewLifecycleOwner.lifecycleScope.launch {
            MicrolistoService.estadoGrabacion.collect { estado ->
                if (_binding == null) return@collect
                actualizarUI(estado)
            }
        }
    }

    private fun actualizarUI(estado: MicrolistoService.Companion.EstadoGrabacion) {
        binding.botonGrabar.text = if (estado.grabando) {
            getString(R.string.grabar_boton_parar)
        } else {
            getString(R.string.grabar_boton_iniciar)
        }

        binding.textoTiempo.text = formatearTiempo(estado.tiempoMs)

        val texto = buildString {
            if (estado.transcripcionAcumulada.isNotBlank()) {
                append(estado.transcripcionAcumulada)
            }
            if (estado.textoParcial.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("… ").append(estado.textoParcial)
            }
        }
        binding.textoTranscripcionVivo.text = if (texto.isBlank()) {
            getString(R.string.grabar_placeholder)
        } else {
            texto
        }

        // Auto-scroll al final
        val scrollView = (binding.textoTranscripcionVivo.parent as? android.widget.ScrollView)
        scrollView?.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }

        estado.error?.let { error ->
            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
        }
    }

    private fun tienePermisoAudio(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun formatearTiempo(ms: Long): String {
        val totalSegundos = ms / 1000
        val horas = totalSegundos / 3600
        val minutos = (totalSegundos % 3600) / 60
        val segundos = totalSegundos % 60
        return String.format("%02d:%02d:%02d", horas, minutos, segundos)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}