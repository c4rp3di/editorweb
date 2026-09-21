package com.carpe.microlisto.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.carpe.microlisto.databinding.FragmentGrabarBinding

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
        // El Bloque 2 conectará el botón con el servicio de grabación
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}