package com.carpe.gestorarchivos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.ArchivoItem
import com.carpe.gestorarchivos.data.Categoria
import com.carpe.gestorarchivos.data.GestorArchivos
import java.io.File

class PantallaReproductor : Fragment() {

    companion object {
        private const val ARG_RUTA = "ruta"
        fun nueva(ruta: String) = PantallaReproductor().apply {
            arguments = Bundle().apply { putString(ARG_RUTA, ruta) }
        }
    }

    private var video: VideoView? = null
    private var controles: MediaController? = null

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_reproductor, contenedor, false)
        val archivo = File(arguments?.getString(ARG_RUTA) ?: "")
        val esAudio = ArchivoItem.categoriaDe(archivo.extension.lowercase()) == Categoria.AUDIO
        val vv = v.findViewById<VideoView>(R.id.videoView)
        video = vv

        v.findViewById<TextView>(R.id.nombreRep).text = archivo.name
        v.findViewById<TextView>(R.id.nombreAudio).text = archivo.name
        v.findViewById<View>(R.id.infoAudio).visibility = if (esAudio) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.botonVolverRep).setOnClickListener { volverAtras() }
        v.findViewById<View>(R.id.botonExternoRep).setOnClickListener {
            if (!GestorArchivos.abrirConOtraApp(requireContext(), archivo)) toast("No hay ninguna app para abrirlo")
        }

        val mc = MediaController(requireContext())
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        controles = mc
        vv.setOnErrorListener { _, _, _ ->
            toast("No se puede reproducir este archivo. Prueba «Abrir con otra app».", true)
            true
        }
        vv.setOnPreparedListener {
            vv.start()
            if (esAudio) mc.show(0)
        }
        if (archivo.exists()) vv.setVideoPath(archivo.absolutePath) else toast("El archivo no existe")
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { volverAtras() }
        })
    }

    override fun onPause() {
        super.onPause()
        video?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { controles?.hide() } catch (_: Exception) {}
        video?.stopPlayback()
        video = null
        controles = null
    }
}
