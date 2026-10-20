package com.carpe.gestorarchivos.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.GestorArchivos
import com.carpe.gestorarchivos.data.LectorOffice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Visor de solo lectura para DOCX y XLSX (convierte el contenido a HTML y lo muestra en un WebView). */
class PantallaVisorDocumento : Fragment() {

    companion object {
        private const val ARG_RUTA = "ruta"
        fun nueva(ruta: String) = PantallaVisorDocumento().apply {
            arguments = Bundle().apply { putString(ARG_RUTA, ruta) }
        }
    }

    private var web: WebView? = null
    private lateinit var cargando: ProgressBar
    private lateinit var pestanas: LinearLayout
    private lateinit var scrollPestanas: HorizontalScrollView
    private var libro: LectorOffice.LibroXlsx? = null
    private val cacheHojas = HashMap<Int, String>()
    private var oscuro = false
    private var hojaActual = 0
    private var archivo: File = File("")

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_visor_documento, contenedor, false)
        archivo = File(arguments?.getString(ARG_RUTA) ?: "")
        oscuro = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        cargando = v.findViewById(R.id.cargandoDoc)
        pestanas = v.findViewById(R.id.pestanasHojas)
        scrollPestanas = v.findViewById(R.id.scrollPestanas)
        v.findViewById<TextView>(R.id.nombreDoc).text = archivo.name
        v.findViewById<View>(R.id.botonVolverDoc).setOnClickListener { volverAtras() }
        v.findViewById<View>(R.id.botonExternoDoc).setOnClickListener {
            if (!GestorArchivos.abrirConOtraApp(requireContext(), archivo)) toast("No hay ninguna app para abrirlo")
        }

        val w = v.findViewById<WebView>(R.id.webDocumento)
        web = w
        val esHtml = esHtml()
        w.setBackgroundColor(if (oscuro && !esHtml) 0xFF12121F.toInt() else Color.WHITE)
        w.settings.apply {
            javaScriptEnabled = false
            // En la vista previa HTML se permite leer los archivos de su misma carpeta (CSS, imágenes)
            allowFileAccess = esHtml
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val esquema = u.scheme
                if (esquema == "http" || esquema == "https" || esquema == "mailto") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                }
                return true
            }
        }
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { volverAtras() }
        })
        cargarDocumento()
    }

    private fun esHtml(): Boolean = archivo.extension.lowercase().let { it == "html" || it == "htm" }

    private fun cargarDocumento() {
        val esHoja = archivo.extension.lowercase().let { it == "xlsx" || it == "xlsm" }
        val html = esHtml()
        cargando.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                runCatching {
                    if (html) {
                        val texto = GestorArchivos.leerTextoDetectado(archivo)?.texto
                            ?: throw IllegalArgumentException("No es un archivo de texto")
                        Pair<LectorOffice.LibroXlsx?, String>(null, texto)
                    } else if (esHoja) {
                        val l = LectorOffice.LibroXlsx.abrir(archivo)
                        Pair<LectorOffice.LibroXlsx?, String>(l, l.hojaAHtml(0, oscuro))
                    } else {
                        Pair<LectorOffice.LibroXlsx?, String>(null, LectorOffice.docxAHtml(archivo, oscuro))
                    }
                }
            }
            cargando.visibility = View.GONE
            val par = resultado.getOrNull()
            if (par == null) {
                toast("No se pudo leer el documento. Prueba «Abrir con otra app».", true)
                volverAtras()
                return@launch
            }
            libro = par.first
            if (html) {
                web?.loadDataWithBaseURL("file://" + (archivo.parent ?: "") + "/", par.second, "text/html", "utf-8", null)
            } else {
                mostrarHtml(par.second)
            }
            val l = libro
            if (l != null) {
                cacheHojas[0] = par.second
                if (l.nombresHojas.size > 1) construirPestanas(l)
            }
        }
    }

    private fun mostrarHtml(html: String) {
        web?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun construirPestanas(l: LectorOffice.LibroXlsx) {
        scrollPestanas.visibility = View.VISIBLE
        pestanas.removeAllViews()
        val densidad = resources.displayMetrics.density
        l.nombresHojas.forEachIndexed { i, nombre ->
            val t = TextView(requireContext())
            t.text = nombre
            t.textSize = 13f
            t.setPadding((12 * densidad).toInt(), (10 * densidad).toInt(), (12 * densidad).toInt(), (10 * densidad).toInt())
            t.setOnClickListener { abrirHoja(i) }
            pestanas.addView(t)
        }
        resaltarPestana()
    }

    private fun resaltarPestana() {
        for (i in 0 until pestanas.childCount) {
            val t = pestanas.getChildAt(i) as TextView
            val activa = i == hojaActual
            t.setTextColor(requireContext().getColor(if (activa) R.color.primario else R.color.texto_secundario))
            t.setTypeface(null, if (activa) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun abrirHoja(i: Int) {
        val l = libro ?: return
        hojaActual = i
        resaltarPestana()
        val enCache = cacheHojas[i]
        if (enCache != null) {
            mostrarHtml(enCache)
            return
        }
        cargando.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val html = withContext(Dispatchers.IO) {
                runCatching { l.hojaAHtml(i, oscuro) }.getOrElse { LectorOffice.paginaMensaje("No se pudo leer esta hoja.", oscuro) }
            }
            cargando.visibility = View.GONE
            cacheHojas[i] = html
            if (hojaActual == i) mostrarHtml(html)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }
}
