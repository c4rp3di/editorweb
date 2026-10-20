package com.carpe.gestorarchivos.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.GestorArchivos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PantallaVisorImagen : Fragment() {

    companion object {
        private const val ARG_RUTAS = "rutas"
        private const val ARG_INDICE = "indice"

        fun nueva(rutas: List<String>, indice: Int) = PantallaVisorImagen().apply {
            arguments = Bundle().apply {
                putStringArrayList(ARG_RUTAS, ArrayList(rutas))
                putInt(ARG_INDICE, indice)
            }
        }
    }

    private var rutas: List<String> = emptyList()
    private var indice = 0
    private var trabajo: Job? = null
    private lateinit var imagen: ZoomImageView
    private lateinit var cargando: ProgressBar
    private lateinit var nombre: TextView
    private lateinit var contador: TextView

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_visor_imagen, contenedor, false)
        imagen = v.findViewById(R.id.imagenZoom)
        cargando = v.findViewById(R.id.cargandoImagen)
        nombre = v.findViewById(R.id.nombreImg)
        contador = v.findViewById(R.id.contadorImg)
        rutas = arguments?.getStringArrayList(ARG_RUTAS) ?: emptyList()
        indice = (arguments?.getInt(ARG_INDICE) ?: 0).coerceIn(0, maxOf(0, rutas.size - 1))

        v.findViewById<View>(R.id.botonVolverImg).setOnClickListener { volverAtras() }
        v.findViewById<View>(R.id.botonExternoImg).setOnClickListener {
            val f = rutas.getOrNull(indice)?.let { File(it) } ?: return@setOnClickListener
            if (!GestorArchivos.abrirConOtraApp(requireContext(), f)) toast("No hay ninguna app para abrirlo")
        }
        v.findViewById<View>(R.id.botonImgAnterior).setOnClickListener { ir(indice - 1) }
        v.findViewById<View>(R.id.botonImgSiguiente).setOnClickListener { ir(indice + 1) }
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { volverAtras() }
        })
        if (rutas.isEmpty()) { volverAtras(); return }
        cargar()
    }

    private fun ir(nuevo: Int) {
        if (nuevo < 0 || nuevo >= rutas.size) return
        indice = nuevo
        cargar()
    }

    private fun cargar() {
        val ruta = rutas[indice]
        nombre.text = File(ruta).name
        contador.text = "${indice + 1} / ${rutas.size}"
        cargando.visibility = View.VISIBLE
        trabajo?.cancel()
        trabajo = viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodificar(File(ruta)) }
            cargando.visibility = View.GONE
            if (bmp == null) {
                toast("No se pudo mostrar la imagen")
            } else {
                imagen.setImageBitmap(bmp)
            }
        }
    }

    private fun decodificar(f: File): Bitmap? {
        return try {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, limites)
            if (limites.outWidth <= 0) return null
            var s = 1
            while (limites.outWidth / s > 2560 || limites.outHeight / s > 2560) s *= 2
            val opciones = BitmapFactory.Options().apply { inSampleSize = s }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opciones) ?: return null
            val grados = try {
                when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) { 0f }
            if (grados == 0f) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(grados) }, true)
        } catch (e: Throwable) {
            null
        }
    }
}
