package com.carpe.microlisto.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.databinding.FragmentHistorialBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            binding.listaConversaciones.text = if (lista.isEmpty()) {
                "Todavía no hay conversaciones grabadas."
            } else {
                lista.joinToString("\n\n") { c ->
                    val fecha = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(c.fechaMs))
                    val dur = c.duracionMs / 1000
                    "$fecha · ${dur}s · ${c.numHablantes} hablantes\n${c.transcripcion.take(120)}…"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}