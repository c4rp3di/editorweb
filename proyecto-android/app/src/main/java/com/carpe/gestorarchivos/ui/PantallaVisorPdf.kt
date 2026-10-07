package com.carpe.gestorarchivos.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.GestorArchivos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Adaptador de páginas de un PDF renderizadas con PdfRenderer (una a la vez, protegido por mutex). */
class AdaptadorPaginasPdf(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val ratios: List<Float>,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<AdaptadorPaginasPdf.VH>() {

    private val mutex = Mutex()
    @Volatile private var cerrado = false
    private var ancho = 1080
    private var anchoRender = 1080

    private val cache = object : LruCache<String, Bitmap>(
        maxOf(1024, (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt())
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    class VH(val imagen: ImageView) : RecyclerView.ViewHolder(imagen) {
        var job: Job? = null
    }

    fun fijarAncho(px: Int, pxRender: Int) {
        ancho = px
        anchoRender = pxRender
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val iv = ImageView(parent.context)
        iv.scaleType = ImageView.ScaleType.FIT_XY
        iv.setBackgroundColor(Color.WHITE)
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.job?.cancel()
        val densidad = holder.imagen.resources.displayMetrics.density
        val alto = (ancho * ratios[position]).toInt().coerceAtLeast(1)
        holder.imagen.layoutParams = RecyclerView.LayoutParams(ancho, alto).apply {
            bottomMargin = (6 * densidad).toInt()
        }
        holder.imagen.setImageBitmap(null)
        val wRender = anchoRender
        val clave = "$position@$wRender"
        val enCache = cache.get(clave)
        if (enCache != null) {
            holder.imagen.setImageBitmap(enCache)
            return
        }
        holder.job = scope.launch {
            val bmp = withContext(Dispatchers.IO) { renderizar(position, wRender) }
            if (bmp != null) {
                cache.put(clave, bmp)
                if (holder.bindingAdapterPosition == position) holder.imagen.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        holder.job = null
    }

    private suspend fun renderizar(pos: Int, w: Int): Bitmap? = mutex.withLock {
        if (cerrado) return@withLock null
        try {
            val pagina = renderer.openPage(pos)
            try {
                val h = (w * ratios[pos]).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                pagina.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                pagina.close()
            }
        } catch (e: Throwable) {
            null
        }
    }

    override fun getItemCount() = ratios.size

    fun cerrar() {
        cerrado = true
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                try { renderer.close() } catch (_: Exception) {}
                try { descriptor.close() } catch (_: Exception) {}
            }
        }
    }
}

class PantallaVisorPdf : Fragment() {

    companion object {
        private const val ARG_RUTA = "ruta"
        private const val ZOOM_MIN = 1f
        private const val ZOOM_MAX = 3f
        private const val RENDER_MAX_PX = 2400

        fun nueva(ruta: String) = PantallaVisorPdf().apply {
            arguments = Bundle().apply { putString(ARG_RUTA, ruta) }
        }
    }

    private var adaptador: AdaptadorPaginasPdf? = null
    private lateinit var lista: RecyclerView
    private lateinit var scrollH: HorizontalScrollView
    private lateinit var indicador: TextView
    private lateinit var cargando: ProgressBar
    private var zoom = 1f
    private var zoomPendiente = 1f
    private var total = 0
    private var archivo: File? = null

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_visor_pdf, contenedor, false)
        archivo = File(arguments?.getString(ARG_RUTA) ?: "")
        lista = v.findViewById(R.id.listaPaginasPdf)
        scrollH = v.findViewById(R.id.scrollPdf)
        indicador = v.findViewById(R.id.indicadorPdf)
        cargando = v.findViewById(R.id.cargandoPdf)
        v.findViewById<TextView>(R.id.nombrePdf).text = archivo?.name ?: ""
        lista.layoutManager = LinearLayoutManager(requireContext())

        v.findViewById<View>(R.id.botonVolverPdf).setOnClickListener { volverAtras() }
        v.findViewById<View>(R.id.botonExternoPdf).setOnClickListener {
            val f = archivo ?: return@setOnClickListener
            if (!GestorArchivos.abrirConOtraApp(requireContext(), f)) toast("No hay ninguna app para abrirlo")
        }
        v.findViewById<View>(R.id.botonZoomMas).setOnClickListener { cambiarZoom(zoom + 0.5f) }
        v.findViewById<View>(R.id.botonZoomMenos).setOnClickListener { cambiarZoom(zoom - 0.5f) }

        val detector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomPendiente = (zoomPendiente * d.scaleFactor).coerceIn(ZOOM_MIN, ZOOM_MAX)
                return true
            }

            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                zoomPendiente = zoom
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                cambiarZoom(zoomPendiente)
            }
        })
        lista.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
        lista.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { actualizarIndicador() }
        })
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { volverAtras() }
        })
        abrir()
    }

    private class PdfAbierto(val renderer: PdfRenderer, val descriptor: ParcelFileDescriptor, val ratios: List<Float>)

    private fun abrir() {
        val f = archivo ?: return
        cargando.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                runCatching {
                    val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    try {
                        val r = PdfRenderer(fd)
                        val ratios = ArrayList<Float>(r.pageCount)
                        for (i in 0 until r.pageCount) {
                            val p = r.openPage(i)
                            ratios.add(p.height.toFloat() / p.width.toFloat())
                            p.close()
                        }
                        PdfAbierto(r, fd, ratios)
                    } catch (e: Throwable) {
                        try { fd.close() } catch (_: Exception) {}
                        throw e
                    }
                }
            }
            cargando.visibility = View.GONE
            val abierto = resultado.getOrNull()
            if (abierto == null) {
                val causa = resultado.exceptionOrNull()
                if (causa is SecurityException) toast("El PDF está protegido con contraseña", true)
                else toast("No se pudo abrir el PDF (puede estar dañado)", true)
                volverAtras()
                return@launch
            }
            total = abierto.ratios.size
            if (total == 0) { toast("El PDF no tiene páginas"); volverAtras(); return@launch }
            val ad = AdaptadorPaginasPdf(abierto.renderer, abierto.descriptor, abierto.ratios, viewLifecycleOwner.lifecycleScope)
            adaptador = ad
            lista.adapter = ad
            aplicarZoom()
            actualizarIndicador()
        }
    }

    private fun cambiarZoom(nuevo: Float) {
        val z = nuevo.coerceIn(ZOOM_MIN, ZOOM_MAX)
        if (Math.abs(z - zoom) < 0.01f) return
        zoom = z
        aplicarZoom()
    }

    private fun aplicarZoom() {
        val ad = adaptador ?: return
        val base = scrollH.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ancho = (base * zoom).toInt()
        val primero = (lista.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
        val lp = lista.layoutParams
        lp.width = ancho
        lista.layoutParams = lp
        ad.fijarAncho(ancho, minOf(ancho, RENDER_MAX_PX))
        (lista.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(primero, 0)
        indicador.text = "${primero + 1}/$total · ${(zoom * 100).toInt()}%"
    }

    private fun actualizarIndicador() {
        if (total == 0) return
        val primero = (lista.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
        indicador.text = "${primero + 1}/$total · ${(zoom * 100).toInt()}%"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lista.adapter = null
        adaptador?.cerrar()
        adaptador = null
    }
}
