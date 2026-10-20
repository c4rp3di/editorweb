package com.carpe.gestorarchivos.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.AdaptadorArchivos
import com.carpe.gestorarchivos.data.Ajustes
import com.carpe.gestorarchivos.data.Almacenamientos
import com.carpe.gestorarchivos.data.ArchivoItem
import com.carpe.gestorarchivos.data.Categoria
import com.carpe.gestorarchivos.data.Compresor
import com.carpe.gestorarchivos.data.Filtro
import com.carpe.gestorarchivos.data.Iconos
import com.carpe.gestorarchivos.data.Formato
import com.carpe.gestorarchivos.data.GestorArchivos
import com.carpe.gestorarchivos.data.Papelera
import com.carpe.gestorarchivos.data.ResumenCarpetas
import com.carpe.gestorarchivos.data.Permisos
import com.carpe.gestorarchivos.data.PortapapelesInterno
import com.carpe.gestorarchivos.data.ProveedorDocumentos
import com.carpe.gestorarchivos.data.RepositorioFavoritos
import com.carpe.gestorarchivos.data.RepositorioRecientes
import com.carpe.gestorarchivos.data.ResolvedorUri
import com.carpe.gestorarchivos.data.SolicitudExterna
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PantallaGestor : Fragment() {

    private var carpetaActual: File? = null
    private lateinit var adaptador: AdaptadorArchivos
    private lateinit var recycler: RecyclerView
    private lateinit var contenedorRuta: LinearLayout
    private lateinit var scrollRuta: HorizontalScrollView
    private lateinit var estadoVacio: View
    private lateinit var textoVacio: TextView
    private lateinit var iconoVacio: TextView
    private lateinit var entradaBusqueda: EditText
    private lateinit var botonLimpiarBusqueda: ImageView
    private lateinit var botonBusquedaRecursiva: ImageView
    private lateinit var indicadorBusqueda: ProgressBar
    private lateinit var barraNormal: View
    private lateinit var barraSeleccion: View
    private lateinit var textoSeleccionados: TextView
    private lateinit var fabPegar: FloatingActionButton
    private lateinit var botonFavoritos: ImageView
    private lateinit var grupoFiltros: ChipGroup

    private lateinit var repositorio: RepositorioRecientes
    private lateinit var repositorioFavoritos: RepositorioFavoritos
    private lateinit var ajustes: Ajustes
    private lateinit var papelera: Papelera
    private lateinit var callbackAtras: OnBackPressedCallback
    private lateinit var abridor: AbridorArchivos

    // Modos externos (otra app pide elegir, guardar, recibir…)
    private lateinit var barraModo: View
    private lateinit var textoModo: TextView
    private lateinit var entradaNombreModo: EditText
    private lateinit var botonAceptarModo: Button
    private var ordenElegidoEnSesion = false
    private var archivoPendiente: String? = null
    private var popupFavoritos: PopupWindow? = null

    private val solicitud: SolicitudExterna? get() = (activity as? AnfitrionExterno)?.solicitud
    private val enModoExterno: Boolean get() = solicitud != null
    private fun anfitrion(): AnfitrionExterno? = activity as? AnfitrionExterno

    private var filtro = Filtro.TODOS
    private var extensionesFiltro: Set<String> = emptySet()
    private var busqueda = ""
    private var busquedaRecursiva = false
    private var todosLosItems: List<ArchivoItem> = emptyList()
    private var trabajoListado: Job? = null
    private var trabajoBusqueda: Job? = null
    private var observador: FileObserver? = null
    private var carpetaObservada: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refrescoDiferido = Runnable {
        if (view != null && busqueda.isEmpty()) refrescar()
    }

    private class ResultadoBusqueda(val items: List<ArchivoItem>, val truncado: Boolean)

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archivoPendiente = arguments?.getString(ARG_ABRIR)
        // El buscador global pide abrir una carpeta concreta
        parentFragmentManager.setFragmentResultListener(PantallaBusqueda.REQ_CARPETA, this) { _, datos ->
            val carpeta = datos.getString(PantallaBusqueda.CLAVE_RUTA)?.let { File(it) }
            if (carpeta != null && carpeta.isDirectory) irA(carpeta)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?
    ): View {
        val vista = inflater.inflate(R.layout.fragment_gestor, contenedor, false)
        val ctx = requireContext()
        repositorio = RepositorioRecientes(ctx)
        repositorioFavoritos = RepositorioFavoritos(ctx)
        ajustes = Ajustes(ctx)
        papelera = Papelera(ctx)

        callbackAtras = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    adaptador.enModoSeleccion() -> adaptador.limpiarSeleccion()
                    busqueda.isNotEmpty() -> entradaBusqueda.setText("")
                    else -> subirNivel()
                }
            }
        }

        recycler = vista.findViewById(R.id.listaArchivos)
        recycler.layoutManager = LinearLayoutManager(ctx)
        contenedorRuta = vista.findViewById(R.id.contenedorRuta)
        scrollRuta = vista.findViewById(R.id.scrollRuta)
        estadoVacio = vista.findViewById(R.id.estadoVacio)
        textoVacio = vista.findViewById(R.id.textoVacio)
        iconoVacio = vista.findViewById(R.id.iconoVacio)
        entradaBusqueda = vista.findViewById(R.id.entradaBusqueda)
        botonLimpiarBusqueda = vista.findViewById(R.id.botonLimpiarBusqueda)
        botonBusquedaRecursiva = vista.findViewById(R.id.botonBusquedaRecursiva)
        indicadorBusqueda = vista.findViewById(R.id.indicadorBusqueda)
        barraNormal = vista.findViewById(R.id.barraNormal)
        barraSeleccion = vista.findViewById(R.id.barraSeleccion)
        textoSeleccionados = vista.findViewById(R.id.textoSeleccionados)
        fabPegar = vista.findViewById(R.id.fabPegar)
        botonFavoritos = vista.findViewById(R.id.botonFavoritos)
        grupoFiltros = vista.findViewById(R.id.grupoFiltros)

        adaptador = AdaptadorArchivos(
            alTocar = { item -> abrirItem(item) },
            alCambiarSeleccion = { sel -> actualizarBarraSeleccion(sel) }
        )
        if (!enModoExterno) adaptador.alPulsarMas = { _, ancla -> mostrarMenuSeleccion(ancla) }
        abridor = AbridorArchivos(this, alIrACarpeta = { irA(it) }, alZip = { dialogoZip(it) })
        recycler.adapter = adaptador

        vista.findViewById<View>(R.id.botonSubir).setOnClickListener { subirNivel() }
        vista.findViewById<View>(R.id.botonOrdenar).setOnClickListener { mostrarMenuOrden() }
        vista.findViewById<View>(R.id.botonMas).setOnClickListener { mostrarMenuMas(it) }
        botonFavoritos.setOnClickListener { mostrarMenuFavoritos() }
        vista.findViewById<FloatingActionButton>(R.id.fabCarpeta).setOnClickListener { alPulsarAlmacenamiento() }
        vista.findViewById<FloatingActionButton>(R.id.fabCrear).setOnClickListener { mostrarMenuCrear() }
        fabPegar.setOnClickListener { pegarAqui() }
        fabPegar.setOnLongClickListener {
            PortapapelesInterno.vaciar()
            actualizarEstadoFabPegar()
            toast("Portapapeles vaciado")
            true
        }

        botonBusquedaRecursiva.setColorFilter(ctx.getColor(R.color.texto_secundario))
        botonBusquedaRecursiva.setOnClickListener {
            busquedaRecursiva = !busquedaRecursiva
            botonBusquedaRecursiva.setColorFilter(
                if (busquedaRecursiva) ctx.getColor(R.color.primario) else ctx.getColor(R.color.texto_secundario)
            )
            toast(if (busquedaRecursiva) "Búsqueda en subcarpetas activada" else "Búsqueda solo en esta carpeta")
            if (busqueda.isNotEmpty()) aplicarFiltro()
        }
        entradaBusqueda.doAfterTextChanged { texto ->
            busqueda = texto?.toString()?.trim() ?: ""
            botonLimpiarBusqueda.visibility = if (busqueda.isEmpty()) View.GONE else View.VISIBLE
            aplicarFiltro()
            actualizarCallbackAtras()
        }
        botonLimpiarBusqueda.setOnClickListener { entradaBusqueda.setText("") }

        grupoFiltros.setOnCheckedStateChangeListener { _, ids ->
            val nuevo = filtroDeChip(ids.firstOrNull())
            if (nuevo != filtro) extensionesFiltro = emptySet()
            filtro = nuevo
            actualizarEtiquetasChips()
            aplicarFiltro()
        }
        // Pulsar un chip de categoría (también si ya estaba marcado) permite afinar por extensión
        for (id in CHIPS_CATEGORIA) {
            val chip = vista.findViewById<Chip>(id)
            chip.setOnClickListener { chip.post { if (view != null && filtro != Filtro.TODOS) mostrarSelectorExtensiones() } }
        }

        configurarBarraSeleccion(vista)
        configurarModoExterno(vista)

        if (carpetaActual == null && Permisos.tiene(ctx)) {
            val raiz = Environment.getExternalStorageDirectory()
            val ultima = repositorio.leerUltimaCarpeta()
            carpetaActual = carpetaPedida()
                ?: if (ultima != null && ultima.exists() && ultima.canRead()) ultima else raiz
        }
        actualizarEstadoFabPegar()
        actualizarIconoFavorito()
        return vista
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callbackAtras)
        // Vaciado automático de la papelera (elementos de más de 30 días)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { papelera.purgarAntiguos(30) }
    }

    override fun onResume() {
        super.onResume()
        if (Permisos.tiene(requireContext()) && carpetaActual == null) {
            carpetaActual = carpetaPedida() ?: Environment.getExternalStorageDirectory()
        }
        refrescar()
        actualizarEstadoFabPegar()
        actualizarIconoFavorito()
        observarCarpeta()
        actualizarCallbackAtras()
    }

    override fun onPause() {
        super.onPause()
        detenerObservador()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        popupFavoritos?.dismiss()
        popupFavoritos = null
        detenerObservador()
        trabajoListado?.cancel()
        trabajoBusqueda?.cancel()
    }

    /** Carpeta que otra app pidió abrir (MainActivity la pasa en arguments), si existe y se puede leer. */
    private fun carpetaPedida(): File? {
        val ruta = arguments?.getString(ARG_RUTA_INICIAL) ?: return null
        val f = File(ruta)
        return if (f.isDirectory && f.canRead()) f else null
    }

    // ------------------------------------------------------------------ observador de carpeta

    private fun observarCarpeta() {
        val carpeta = carpetaActual
        if (carpeta == null || !carpeta.exists()) { detenerObservador(); return }
        if (observador != null && carpetaObservada == carpeta.absolutePath) return
        observador?.stopWatching()
        @Suppress("DEPRECATION")
        val obs = object : FileObserver(
            carpeta.absolutePath,
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
                FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        ) {
            override fun onEvent(event: Int, path: String?) {
                handler.removeCallbacks(refrescoDiferido)
                handler.postDelayed(refrescoDiferido, 400)
            }
        }
        obs.startWatching()
        observador = obs
        carpetaObservada = carpeta.absolutePath
    }

    private fun detenerObservador() {
        handler.removeCallbacks(refrescoDiferido)
        observador?.stopWatching()
        observador = null
        carpetaObservada = null
    }

    private fun actualizarCallbackAtras() {
        if (!::callbackAtras.isInitialized || !::adaptador.isInitialized) return
        val carpeta = carpetaActual
        val puedeSubir = carpeta != null && !Almacenamientos.esRaiz(requireContext(), carpeta) && carpeta.parentFile != null
        callbackAtras.isEnabled = adaptador.enModoSeleccion() || busqueda.isNotEmpty() || puedeSubir
    }

    // ------------------------------------------------------------------ permisos / navegación

    private fun alPulsarAlmacenamiento() {
        val ctx = requireContext()
        if (!Permisos.tiene(ctx)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:" + ctx.packageName)
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                toast("Concede el permiso de almacenamiento en los ajustes de la app", true)
            }
            return
        }
        if (Almacenamientos.volumenes(ctx).size > 1) mostrarAlmacenamientos()
        else irA(Environment.getExternalStorageDirectory())
    }

    private fun mostrarAlmacenamientos() {
        val vols = Almacenamientos.volumenes(requireContext())
        val etiquetas = vols.map { "💾 ${it.nombre}\n${espacioLibre(it.raiz)}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Almacenamientos")
            .setItems(etiquetas) { _, i -> irA(vols[i].raiz) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun espacioLibre(raiz: File): String = try {
        val s = StatFs(raiz.absolutePath)
        "${Formato.tamano(s.availableBytes)} libres de ${Formato.tamano(s.totalBytes)}"
    } catch (e: Exception) {
        "—"
    }

    private fun irA(carpeta: File) {
        if (carpeta.absolutePath != carpetaActual?.absolutePath && extensionesFiltro.isNotEmpty()) {
            extensionesFiltro = emptySet()
            actualizarEtiquetasChips()
        }
        carpetaActual = carpeta
        if (entradaBusqueda.text.isNotEmpty()) entradaBusqueda.setText("")
        adaptador.limpiarSeleccion()
        refrescar()
        actualizarIconoFavorito()
        observarCarpeta()
        actualizarCallbackAtras()
    }

    private fun subirNivel() {
        val actual = carpetaActual
        if (actual == null || Almacenamientos.esRaiz(requireContext(), actual)) {
            toast("Ya estás en la raíz")
            return
        }
        val padre = actual.parentFile ?: run {
            toast("Ya estás en la raíz")
            return
        }
        irA(padre)
    }

    private fun construirRuta() {
        contenedorRuta.removeAllViews()
        val carpeta = carpetaActual
        val ctx = requireContext()
        if (carpeta == null) {
            contenedorRuta.addView(crearSegmento("Sin carpeta", null))
            return
        }
        val raiz = Almacenamientos.raizDe(ctx, carpeta)
        val segmentos = ArrayList<Pair<String, File>>()
        var f: File? = carpeta
        while (f != null && f.absolutePath != raiz.absolutePath) {
            segmentos.add(0, Pair(f.name, f))
            f = f.parentFile
        }
        segmentos.add(0, Pair(Almacenamientos.nombreCorto(ctx, raiz), raiz))
        segmentos.forEachIndexed { i, (nombre, dir) ->
            if (i > 0) {
                val sep = TextView(ctx)
                sep.text = "›"
                sep.setTextColor(ctx.getColor(R.color.texto_secundario))
                contenedorRuta.addView(sep)
            }
            val esUltimo = i == segmentos.size - 1
            contenedorRuta.addView(crearSegmento(nombre, if (esUltimo) null else dir))
        }
        scrollRuta.post { scrollRuta.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun crearSegmento(texto: String, destino: File?): TextView {
        val ctx = requireContext()
        val t = TextView(ctx)
        t.text = texto
        t.textSize = 13f
        val d = ctx.resources.displayMetrics.density
        t.setPadding((6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
        t.setTextColor(ctx.getColor(if (destino == null) R.color.texto else R.color.primario))
        if (destino == null) t.setTypeface(null, android.graphics.Typeface.BOLD)
        if (destino != null) t.setOnClickListener { irA(destino) }
        return t
    }

    // ------------------------------------------------------------------ listado

    private fun refrescar() {
        if (view == null) return
        val ctx = requireContext()
        if (!Permisos.tiene(ctx)) {
            todosLosItems = emptyList()
            adaptador.actualizar(emptyList())
            contenedorRuta.removeAllViews()
            contenedorRuta.addView(crearSegmento("Sin permiso de archivos", null))
            estadoVacio.visibility = View.VISIBLE
            iconoVacio.text = "🔒"
            textoVacio.text = "Toca el botón morado para conceder acceso a los archivos"
            return
        }
        val carpeta = carpetaActual
        if (carpeta == null || !carpeta.exists() || !carpeta.canRead()) {
            todosLosItems = emptyList()
            adaptador.actualizar(emptyList())
            construirRuta()
            estadoVacio.visibility = View.VISIBLE
            iconoVacio.text = "📂"
            textoVacio.text = "No se puede acceder a esta carpeta"
            return
        }
        val ocultos = ajustes.mostrarOcultos
        trabajoListado?.cancel()
        trabajoListado = viewLifecycleOwner.lifecycleScope.launch {
            val lista = withContext(Dispatchers.IO) { GestorArchivos.listar(carpeta, ocultos) }
            if (carpeta != carpetaActual) return@launch
            todosLosItems = lista
            construirRuta()
            aplicarFiltro()
            // Los modos externos no deben cambiar la carpeta con la que se abre la app normalmente
            if (!enModoExterno) repositorio.guardarUltimaCarpeta(carpeta)
            actualizarBarraModo()
            abrirPendiente()
        }
    }

    private fun aplicarFiltro() {
        if (view == null) return
        trabajoBusqueda?.cancel()
        indicadorBusqueda.visibility = View.GONE
        val ocultos = ajustes.mostrarOcultos
        if (busqueda.isNotEmpty() && busquedaRecursiva) {
            val carpeta = carpetaActual
            val termino = busqueda
            val f = filtro
            val ext = extensionesFiltro
            val externo = solicitud
            indicadorBusqueda.visibility = View.VISIBLE
            trabajoBusqueda = viewLifecycleOwner.lifecycleScope.launch {
                delay(250)
                val res = withContext(Dispatchers.IO) { buscarRecursivo(carpeta, termino, ocultos, f, ext, externo) }
                indicadorBusqueda.visibility = View.GONE
                if (res.truncado) toast("Mostrando solo los primeros ${res.items.size} resultados")
                mostrarLista(res.items)
            }
            return
        }
        val externo = solicitud
        val base = todosLosItems.filter {
            (ocultos || !it.nombre.startsWith(".")) &&
                filtro.admite(it, extensionesFiltro) &&
                (externo == null || externo.acepta(it)) &&
                (busqueda.isEmpty() || it.nombre.contains(busqueda, ignoreCase = true))
        }
        mostrarLista(base)
    }

    private fun CoroutineScope.buscarRecursivo(
        carpeta: File?, termino: String, ocultos: Boolean, f: Filtro,
        ext: Set<String>, externo: SolicitudExterna?
    ): ResultadoBusqueda {
        val resultados = ArrayList<ArchivoItem>()
        if (carpeta == null) return ResultadoBusqueda(resultados, false)
        val cola = ArrayDeque<File>()
        cola.add(carpeta)
        var dirs = 0
        while (cola.isNotEmpty() && isActive) {
            if (dirs++ >= 20000) return ResultadoBusqueda(resultados, true)
            val actual = cola.removeFirst()
            val hijos = actual.listFiles() ?: continue
            for (hijo in hijos) {
                if (!ocultos && hijo.name.startsWith(".")) continue
                if (hijo.isDirectory) cola.add(hijo)
                if (hijo.name.contains(termino, ignoreCase = true)) {
                    val item = ArchivoItem.desde(hijo)
                    if (f.admite(item, ext) && (externo == null || externo.acepta(item))) {
                        resultados.add(item)
                        if (resultados.size >= 2000) return ResultadoBusqueda(resultados, true)
                    }
                }
            }
        }
        return ResultadoBusqueda(resultados, false)
    }

    private fun mostrarLista(base: List<ArchivoItem>) {
        adaptador.mostrarRutaPadre = busquedaRecursiva && busqueda.isNotEmpty()
        val ordenados = ordenar(base)
        adaptador.establecerFavoritos(repositorioFavoritos.rutas())
        adaptador.actualizar(ordenados)

        val sinPermiso = !Permisos.tiene(requireContext())
        val sinCarpeta = carpetaActual == null
        estadoVacio.visibility = if (ordenados.isEmpty()) View.VISIBLE else View.GONE
        iconoVacio.text = when {
            sinPermiso -> "🔒"
            sinCarpeta -> "📂"
            busqueda.isNotEmpty() -> "🔍"
            else -> "📁"
        }
        textoVacio.text = when {
            sinPermiso -> "Toca el botón morado para conceder acceso a los archivos"
            sinCarpeta -> "No hay carpeta seleccionada"
            busqueda.isNotEmpty() -> "Sin resultados para «$busqueda»"
            solicitud?.tipo == SolicitudExterna.Tipo.ELEGIR_CARPETA -> "No hay subcarpetas aquí. Pulsa «Usar esta carpeta» para elegirla."
            filtro != Filtro.TODOS && extensionesFiltro.isNotEmpty() -> "No hay archivos con esas extensiones en la carpeta"
            filtro != Filtro.TODOS -> "No hay archivos de este tipo en la carpeta"
            else -> getString(R.string.carpeta_vacia)
        }
        actualizarCallbackAtras()
    }

    private fun ordenar(items: List<ArchivoItem>): List<ArchivoItem> {
        // En los modos externos, hasta que el usuario elija otro orden: lo más reciente primero
        val porDefectoExterno = enModoExterno && !ordenElegidoEnSesion
        val orden = if (porDefectoExterno) ORDEN_FECHA else ajustes.orden
        val ascendente = if (porDefectoExterno) false else ajustes.ordenAscendente
        val comparador: Comparator<ArchivoItem> = when (orden) {
            ORDEN_FECHA -> compareBy { it.ultimaModificacion }
            ORDEN_TAMANO -> compareBy { it.tamano }
            ORDEN_TIPO -> compareBy<ArchivoItem> { it.categoria.ordinal }
                .thenBy { it.extension }
                .thenComparator { a, b -> compararNatural(a.nombre, b.nombre) }
            else -> Comparator { a, b -> compararNatural(a.nombre, b.nombre) }
        }
        val carpetas = items.filter { it.esCarpeta }.sortedWith(comparador)
        val archivos = items.filter { !it.esCarpeta }.sortedWith(comparador)
        return if (ascendente) carpetas + archivos else carpetas.reversed() + archivos.reversed()
    }

    // ------------------------------------------------------------------ selección

    private fun configurarBarraSeleccion(vista: View) {
        vista.findViewById<View>(R.id.botonCerrarSeleccion).setOnClickListener { adaptador.limpiarSeleccion() }
        vista.findViewById<View>(R.id.botonSelMover).setOnClickListener {
            val sel = adaptador.obtenerSeleccionados()
            if (sel.isNotEmpty()) iniciarOperacionLote(sel, esMover = true)
        }
        vista.findViewById<View>(R.id.botonSelCopiar).setOnClickListener {
            val sel = adaptador.obtenerSeleccionados()
            if (sel.isNotEmpty()) iniciarOperacionLote(sel, esMover = false)
        }
        vista.findViewById<View>(R.id.botonSelCompartir).setOnClickListener {
            compartirLote(adaptador.obtenerSeleccionados())
        }
        vista.findViewById<View>(R.id.botonSelBorrar).setOnClickListener {
            val sel = adaptador.obtenerSeleccionados()
            if (sel.isNotEmpty()) confirmarBorrar(sel)
        }
        vista.findViewById<View>(R.id.botonSelMas).setOnClickListener { mostrarMenuSeleccion(it) }
    }

    private fun actualizarBarraSeleccion(seleccionados: Set<String>) {
        if (!::barraSeleccion.isInitialized) return
        val n = seleccionados.size
        if (enModoExterno) {
            // En los modos externos no hay mover/copiar/borrar: solo se marca para elegir
            barraSeleccion.visibility = View.GONE
            barraNormal.visibility = View.VISIBLE
            actualizarBarraModo()
            actualizarCallbackAtras()
            return
        }
        if (n == 0) {
            barraSeleccion.visibility = View.GONE
            barraNormal.visibility = View.VISIBLE
        } else {
            barraSeleccion.visibility = View.VISIBLE
            barraNormal.visibility = View.GONE
            textoSeleccionados.text = getString(R.string.seleccionados, n)
        }
        actualizarCallbackAtras()
    }

    private fun mostrarMenuSeleccion(ancla: View) {
        val sel = adaptador.obtenerSeleccionados()
        if (sel.isEmpty()) return
        val menu = PopupMenu(requireContext(), ancla)
        menu.inflate(R.menu.menu_contextual_archivo)
        val unico = sel.size == 1
        val item = sel[0]
        menu.menu.findItem(R.id.accionAbrir).isVisible = unico
        menu.menu.findItem(R.id.accionEditar).isVisible = unico && item.categoria != Categoria.TEXTO && !item.esCarpeta
        menu.menu.findItem(R.id.accionRenombrar).isVisible = unico
        val todosFavoritos = sel.all { repositorioFavoritos.esFavorito(it.clave) }
        menu.menu.findItem(R.id.accionFavorito).title =
            if (todosFavoritos) "Quitar de favoritos" else "Añadir a favoritos"
        menu.menu.findItem(R.id.accionExtraer).isVisible = unico && item.extension == "zip"
        menu.menu.findItem(R.id.accionAbrirConOtra).isVisible = unico && !item.esCarpeta
        menu.menu.findItem(R.id.accionInfo).isVisible = unico
        menu.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                R.id.accionAbrir -> { adaptador.limpiarSeleccion(); abrirItem(item) }
                R.id.accionEditar -> { adaptador.limpiarSeleccion(); abrirEditorTexto(item.archivo) }
                R.id.accionFavorito -> alternarFavoritos(sel, todosFavoritos)
                R.id.accionRenombrar -> pedirNombre("Renombrar", item.nombre) { nuevo ->
                    if (nuevo == item.nombre) return@pedirNombre
                    if (GestorArchivos.renombrar(item.archivo, nuevo)) {
                        repositorioFavoritos.actualizarRuta(
                            item.archivo.absolutePath, item.archivo.resolveSibling(nuevo).absolutePath
                        )
                        adaptador.limpiarSeleccion()
                        refrescar()
                    } else {
                        toast("No se pudo renombrar (¿ya existe ese nombre?)", true)
                    }
                }
                R.id.accionComprimir -> comprimirSeleccion(sel)
                R.id.accionExtraer -> extraerZip(item)
                R.id.accionAbrirConOtra -> abridor.abrirCon(item)
                R.id.accionInfo -> mostrarInfo(item)
                R.id.accionSeleccionarTodo -> adaptador.seleccionarTodo()
            }
            true
        }
        menu.show()
    }

    // ------------------------------------------------------------------ abrir

    private fun abrirItem(item: ArchivoItem) {
        val s = solicitud
        if (s != null && s.tipo != SolicitudExterna.Tipo.ABRIR) {
            when {
                item.esCarpeta -> irA(item.archivo)
                s.tipo == SolicitudExterna.Tipo.ELEGIR_ARCHIVO ->
                    if (s.multiple) adaptador.alternarSeleccion(item.clave) else entregarElegidos(listOf(item))
                s.tipo == SolicitudExterna.Tipo.ELEGIR_CARPETA -> Unit
                else -> toast("Pulsa «Guardar aquí» para guardarlo en esta carpeta")
            }
            return
        }
        abridor.abrir(item, adaptador.obtenerItems())
    }

    private fun abrirEditorTexto(archivo: File) = abridor.abrirEditor(archivo)

    private fun abrirConAppExterna(archivo: File) = abridor.abrirConSelector(archivo)

    private fun dialogoZip(item: ArchivoItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.nombre)
            .setItems(arrayOf("📦 Extraer en una carpeta nueva", "Abrir con otra app")) { _, i ->
                if (i == 0) extraerZip(item) else abrirConAppExterna(item.archivo)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ crear

    private fun mostrarMenuCrear() {
        val carpeta = carpetaActual ?: run {
            toast("No hay carpeta seleccionada")
            return
        }
        val opciones = arrayOf("📁 Nueva carpeta", "📝 Nuevo archivo de texto")
        AlertDialog.Builder(requireContext())
            .setTitle("Crear")
            .setItems(opciones) { _, i ->
                if (i == 0) {
                    pedirNombre("Nueva carpeta", "") { nombre ->
                        if (!GestorArchivos.crearCarpeta(carpeta, nombre)) toast("Ya existe o no se pudo crear", true)
                        refrescar()
                    }
                } else {
                    pedirNombre("Nuevo archivo", "archivo.txt") { nombre ->
                        val nombreFinal = if (nombre.contains(".")) nombre else "$nombre.txt"
                        if (GestorArchivos.crearArchivo(carpeta, nombreFinal)) {
                            refrescar()
                            abrirEditorTexto(File(carpeta, nombreFinal))
                        } else {
                            toast("Ya existe o no se pudo crear", true)
                        }
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ menús

    private fun mostrarMenuOrden() {
        val menu = PopupMenu(requireContext(), requireView().findViewById(R.id.botonOrdenar))
        menu.inflate(R.menu.menu_seleccion_orden)
        menu.setOnMenuItemClickListener { m ->
            ordenElegidoEnSesion = true
            when (m.itemId) {
                R.id.ordenNombreAsc -> { ajustes.orden = ORDEN_NOMBRE; ajustes.ordenAscendente = true }
                R.id.ordenNombreDesc -> { ajustes.orden = ORDEN_NOMBRE; ajustes.ordenAscendente = false }
                R.id.ordenFechaDesc -> { ajustes.orden = ORDEN_FECHA; ajustes.ordenAscendente = false }
                R.id.ordenFechaAsc -> { ajustes.orden = ORDEN_FECHA; ajustes.ordenAscendente = true }
                R.id.ordenTamanoDesc -> { ajustes.orden = ORDEN_TAMANO; ajustes.ordenAscendente = false }
                R.id.ordenTamanoAsc -> { ajustes.orden = ORDEN_TAMANO; ajustes.ordenAscendente = true }
                R.id.ordenTipo -> { ajustes.orden = ORDEN_TIPO; ajustes.ordenAscendente = true }
            }
            aplicarFiltro()
            true
        }
        menu.show()
    }

    private fun mostrarMenuMas(ancla: View) {
        val menu = PopupMenu(requireContext(), ancla)
        menu.inflate(R.menu.menu_mas)
        menu.menu.findItem(R.id.accionOcultos).isChecked = ajustes.mostrarOcultos
        if (enModoExterno) {
            listOf(
                R.id.accionBusquedaGlobal, R.id.accionRecientes, R.id.accionPapelera,
                R.id.accionAnalizador, R.id.accionDuplicados
            ).forEach { menu.menu.removeItem(it) }
        }
        val actual = carpetaActual
        val itemFavActual = menu.menu.findItem(R.id.accionFavoritoActual)
        if (actual == null) itemFavActual.isVisible = false
        else itemFavActual.title = if (repositorioFavoritos.esFavorito(actual.absolutePath))
            "Quitar carpeta actual de favoritos" else "Añadir carpeta actual a favoritos"
        menu.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                R.id.accionDescargas -> {
                    val d = Almacenamientos.descargas()
                    if (d.isDirectory && d.canRead()) irA(d) else toast("No se puede acceder a Descargas")
                }
                R.id.accionFavoritoActual -> alternarFavoritoActual()
                R.id.accionOcultos -> {
                    ajustes.mostrarOcultos = !ajustes.mostrarOcultos
                    refrescar()
                }
                R.id.accionBusquedaGlobal -> navegarA(PantallaBusqueda.nueva(true), "busqueda")
                R.id.accionRecientes -> navegarA(PantallaBusqueda.nueva(false), "recientes")
                R.id.accionPapelera -> navegarA(PantallaPapelera(), "papelera")
                R.id.accionAlmacenamientos -> mostrarAlmacenamientos()
                R.id.accionAnalizador -> carpetaActual?.let {
                    navegarA(PantallaAnalizador.nueva(PantallaAnalizador.MODO_ESPACIO, it.absolutePath), "analizador")
                } ?: toast("No hay carpeta seleccionada")
                R.id.accionDuplicados -> carpetaActual?.let {
                    navegarA(PantallaAnalizador.nueva(PantallaAnalizador.MODO_DUPLICADOS, it.absolutePath), "duplicados")
                } ?: toast("No hay carpeta seleccionada")
            }
            true
        }
        menu.show()
    }

    // ------------------------------------------------------------------ favoritos (lista desplegable)

    private fun mostrarMenuFavoritos() {
        popupFavoritos?.let {
            if (it.isShowing) {
                it.dismiss()
                return
            }
        }
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val anchoPantalla = resources.displayMetrics.widthPixels
        val altoPantalla = resources.displayMetrics.heightPixels
        val raiz = layoutInflater.inflate(R.layout.popup_favoritos, null)
        val lista = raiz.findViewById<ListView>(R.id.listaFavoritos)
        val textoVacio = raiz.findViewById<TextView>(R.id.textoFavoritosVacio)
        val botonLimpiar = raiz.findViewById<TextView>(R.id.botonLimpiarFavoritos)

        val ancho = minOf((340 * d).toInt(), anchoPantalla - (16 * d).toInt())
        val ancla = botonFavoritos
        val pos = IntArray(2)
        ancla.getLocationOnScreen(pos)
        val margen = (8 * d).toInt()
        val izquierda = (pos[0] + ancla.width - ancho).coerceIn(margen, maxOf(margen, anchoPantalla - ancho - margen))
        val desplazamientoX = izquierda - pos[0]

        val popup = PopupWindow(raiz, ancho, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ContextCompat.getDrawable(ctx, R.drawable.bg_popup_favoritos))
        popup.elevation = 12 * d
        popup.isOutsideTouchable = true
        popupFavoritos = popup

        val datos = repositorioFavoritos.listar().toMutableList()
        lateinit var adaptadorFav: AdaptadorFavoritos

        fun refrescarPopup() {
            adaptadorFav.notifyDataSetChanged()
            val hayInexistentes = datos.any { !it.existe }
            textoVacio.visibility = if (datos.isEmpty()) View.VISIBLE else View.GONE
            lista.visibility = if (datos.isEmpty()) View.GONE else View.VISIBLE
            botonLimpiar.visibility = if (hayInexistentes) View.VISIBLE else View.GONE
            val cabecera = 45 * d
            val cuerpo = if (datos.isEmpty()) 130 * d else datos.size * 60 * d
            val pie = if (hayInexistentes) 40 * d else 0f
            val alto = (cabecera + cuerpo + pie).toInt().coerceAtMost((altoPantalla * 0.6f).toInt())
            if (popup.isShowing) popup.update(ancla, desplazamientoX, 0, ancho, alto) else popup.height = alto
        }

        adaptadorFav = AdaptadorFavoritos(datos) { fav ->
            repositorioFavoritos.quitar(fav.ruta)
            datos.removeAll { it.ruta == fav.ruta }
            actualizarIconoFavorito()
            actualizarMarcasFavoritos()
            refrescarPopup()
        }
        lista.adapter = adaptadorFav
        lista.setOnItemClickListener { _, _, i, _ ->
            popup.dismiss()
            abrirFavorito(datos[i])
        }
        lista.setOnItemLongClickListener { _, _, i, _ ->
            // Pulsación larga: mostrar el elemento en su carpeta
            val fav = datos[i]
            popup.dismiss()
            val destino = if (fav.esCarpeta) fav.archivo else fav.carpetaPadre
            if (destino != null && destino.isDirectory && destino.canRead()) irA(destino)
            else toast("Ya no existe esa carpeta")
            true
        }
        botonLimpiar.setOnClickListener {
            val n = repositorioFavoritos.limpiarInexistentes()
            datos.clear()
            datos.addAll(repositorioFavoritos.listar())
            actualizarIconoFavorito()
            actualizarMarcasFavoritos()
            refrescarPopup()
            toast("$n favorito(s) quitado(s)")
        }
        refrescarPopup()
        popup.showAsDropDown(ancla, desplazamientoX, 0)
    }

    private fun abrirFavorito(fav: RepositorioFavoritos.Favorito) {
        val f = fav.archivo
        if (!f.exists()) {
            toast("Ya no existe «${fav.nombre}»")
            return
        }
        if (f.isDirectory) {
            if (f.canRead()) irA(f) else toast("No se puede acceder a esa carpeta")
            return
        }
        val padre = f.parentFile
        val item = ArchivoItem.desde(f)
        val s = solicitud
        if (s == null || s.tipo == SolicitudExterna.Tipo.ABRIR) {
            if (padre != null && padre.absolutePath != carpetaActual?.absolutePath) irA(padre)
            abridor.abrir(item, emptyList())
        } else if (s.tipo == SolicitudExterna.Tipo.ELEGIR_ARCHIVO && !s.multiple) {
            entregarElegidos(listOf(item))
        } else {
            padre?.let { irA(it) }
        }
    }

    private fun alternarFavoritos(sel: List<ArchivoItem>, quitar: Boolean) {
        if (quitar) sel.forEach { repositorioFavoritos.quitar(it.clave) }
        else sel.forEach { repositorioFavoritos.agregar(it.archivo) }
        toast(if (quitar) "Quitado de favoritos" else "Añadido a favoritos")
        adaptador.limpiarSeleccion()
        actualizarIconoFavorito()
        actualizarMarcasFavoritos()
    }

    private fun alternarFavoritoActual() {
        val c = carpetaActual ?: return
        val ahora = repositorioFavoritos.alternar(c)
        toast(if (ahora) "Carpeta añadida a favoritos" else "Carpeta quitada de favoritos")
        actualizarIconoFavorito()
        actualizarMarcasFavoritos()
    }

    private fun actualizarMarcasFavoritos() {
        if (!::adaptador.isInitialized) return
        adaptador.establecerFavoritos(repositorioFavoritos.rutas())
    }

    private fun rutaLegible(f: File?): String {
        val ruta = f?.absolutePath ?: return ""
        val interno = Environment.getExternalStorageDirectory().absolutePath
        return when {
            ruta == interno -> "Interno"
            ruta.startsWith("$interno/") -> "Interno" + ruta.removePrefix(interno)
            else -> ruta
        }
    }

    /** Filas del desplegable de favoritos: icono según el tipo, nombre, ruta y botón para quitar. */
    private inner class AdaptadorFavoritos(
        private val datos: List<RepositorioFavoritos.Favorito>,
        private val alQuitar: (RepositorioFavoritos.Favorito) -> Unit
    ) : BaseAdapter() {
        override fun getCount() = datos.size
        override fun getItem(position: Int) = datos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_favorito, parent, false)
            val fav = datos[position]
            val existe = fav.existe
            v.findViewById<TextView>(R.id.favIcono).text = Iconos.de(fav.categoria)
            v.findViewById<TextView>(R.id.favNombre).text = fav.nombre
            val ruta = rutaLegible(if (fav.esCarpeta) fav.archivo else fav.carpetaPadre)
            v.findViewById<TextView>(R.id.favRuta).text = if (existe) ruta else "No encontrado · $ruta"
            v.findViewById<View>(R.id.favIcono).alpha = if (existe) 1f else 0.4f
            v.findViewById<View>(R.id.favNombre).alpha = if (existe) 1f else 0.5f
            v.findViewById<View>(R.id.favQuitar).setOnClickListener { alQuitar(fav) }
            return v
        }
    }

    private fun actualizarIconoFavorito() {
        if (!::botonFavoritos.isInitialized) return
        val carpeta = carpetaActual ?: return
        val esFav = repositorioFavoritos.esFavorito(carpeta.absolutePath)
        botonFavoritos.setImageResource(
            if (esFav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
        botonFavoritos.setColorFilter(
            requireContext().getColor(if (esFav) R.color.primario else R.color.texto)
        )
    }

    // ------------------------------------------------------------------ filtros por extensión

    private fun filtroDeChip(id: Int?): Filtro = when (id) {
        R.id.chipImagenes -> Filtro.IMAGENES
        R.id.chipVideos -> Filtro.VIDEOS
        R.id.chipAudio -> Filtro.AUDIO
        R.id.chipDocumentos -> Filtro.DOCUMENTOS
        R.id.chipComprimidos -> Filtro.COMPRIMIDOS
        R.id.chipOtros -> Filtro.OTROS
        else -> Filtro.TODOS
    }

    private fun nombreFiltro(f: Filtro): String = when (f) {
        Filtro.IMAGENES -> "Imágenes"
        Filtro.VIDEOS -> "Vídeos"
        Filtro.AUDIO -> "Audio"
        Filtro.DOCUMENTOS -> "Documentos"
        Filtro.COMPRIMIDOS -> "Comprimidos"
        Filtro.OTROS -> "Otros"
        Filtro.TODOS -> "Todo"
    }

    /** El chip activo muestra qué extensiones están marcadas: «Documentos · docx, csv». */
    private fun actualizarEtiquetasChips() {
        val v = view ?: return
        for (id in CHIPS_CATEGORIA) {
            val chip = v.findViewById<Chip>(id) ?: continue
            val f = filtroDeChip(id)
            val base = nombreFiltro(f)
            chip.text = if (f == filtro && extensionesFiltro.isNotEmpty()) {
                val detalle = if (extensionesFiltro.size <= 2) {
                    extensionesFiltro.sorted().joinToString(", ") { Filtro.etiquetaExtension(it) }
                } else {
                    "${extensionesFiltro.size} tipos"
                }
                "$base · $detalle"
            } else base
        }
    }

    /** Lista las extensiones que hay en la carpeta actual para el grupo activo y deja marcar una o varias. */
    private fun mostrarSelectorExtensiones() {
        val ocultos = ajustes.mostrarOcultos
        val cuenta = LinkedHashMap<String, Int>()
        for (item in todosLosItems) {
            if (!ocultos && item.nombre.startsWith(".")) continue
            if (item.esCarpeta || !filtro.incluye(item.categoria)) continue
            cuenta[item.extension] = (cuenta[item.extension] ?: 0) + 1
        }
        if (cuenta.isEmpty()) {
            toast("No hay archivos de este tipo en esta carpeta")
            return
        }
        if (cuenta.size == 1) return // una sola extensión: no hay nada que afinar
        val extensiones = cuenta.keys.sortedWith(compareByDescending<String> { cuenta[it] ?: 0 }.thenBy { it })
        val etiquetas = extensiones.map { "${Filtro.etiquetaExtension(it)}   (${cuenta[it]})" }.toTypedArray()
        val marcadas = BooleanArray(extensiones.size) { extensiones[it] in extensionesFiltro }
        AlertDialog.Builder(requireContext())
            .setTitle("${nombreFiltro(filtro)}: elige tipos")
            .setMultiChoiceItems(etiquetas, marcadas) { _, i, marcado -> marcadas[i] = marcado }
            .setPositiveButton("Aplicar") { _, _ ->
                extensionesFiltro = extensiones.filterIndexed { i, _ -> marcadas[i] }.toSet()
                actualizarEtiquetasChips()
                aplicarFiltro()
            }
            .setNeutralButton("Todas") { _, _ ->
                extensionesFiltro = emptySet()
                actualizarEtiquetasChips()
                aplicarFiltro()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ modos externos

    private fun configurarModoExterno(vista: View) {
        val s = solicitud ?: return
        barraModo = vista.findViewById(R.id.barraModo)
        textoModo = vista.findViewById(R.id.textoModo)
        entradaNombreModo = vista.findViewById(R.id.entradaNombreModo)
        botonAceptarModo = vista.findViewById(R.id.botonAceptarModo)
        vista.findViewById<View>(R.id.botonCancelarModo).setOnClickListener { anfitrion()?.cancelar() }

        if (s.tipo == SolicitudExterna.Tipo.ABRIR) return // solo abre el archivo y deja navegar
        barraModo.visibility = View.VISIBLE
        when (s.tipo) {
            SolicitudExterna.Tipo.ELEGIR_ARCHIVO -> {
                botonAceptarModo.visibility = if (s.multiple) View.VISIBLE else View.GONE
                botonAceptarModo.setOnClickListener { entregarElegidos(adaptador.obtenerSeleccionados()) }
                // Sin selección múltiple, mantener pulsado equivale a tocar
                if (!s.multiple) adaptador.alMantener = { item -> abrirItem(item) }
            }
            SolicitudExterna.Tipo.ELEGIR_CARPETA -> {
                botonAceptarModo.text = "Usar esta carpeta"
                botonAceptarModo.setOnClickListener { entregarCarpeta() }
                adaptador.alMantener = { item -> abrirItem(item) }
            }
            SolicitudExterna.Tipo.CREAR_DOCUMENTO -> {
                entradaNombreModo.visibility = View.VISIBLE
                entradaNombreModo.setText(s.nombreSugerido ?: "")
                botonAceptarModo.text = "Guardar aquí"
                botonAceptarModo.setOnClickListener { guardarNuevoDocumento() }
            }
            SolicitudExterna.Tipo.RECIBIR -> {
                if (s.fuentes.size <= 1) {
                    entradaNombreModo.visibility = View.VISIBLE
                    entradaNombreModo.setText(s.nombreSugerido ?: "")
                }
                botonAceptarModo.text = "Guardar aquí"
                botonAceptarModo.setOnClickListener { guardarRecibidos() }
            }
            SolicitudExterna.Tipo.ABRIR -> Unit
        }
        actualizarBarraModo()
    }

    private fun actualizarBarraModo() {
        if (!::barraModo.isInitialized) return
        val s = solicitud ?: return
        val destino = rutaLegible(carpetaActual)
        when (s.tipo) {
            SolicitudExterna.Tipo.ELEGIR_ARCHIVO -> {
                val n = adaptador.obtenerSeleccionados().count { !it.esCarpeta }
                textoModo.text = if (s.multiple) "Toca los archivos que quieras elegir" else "Toca el archivo que quieras elegir"
                botonAceptarModo.text = if (n > 0) "Elegir ($n)" else "Elegir"
                botonAceptarModo.isEnabled = n > 0
            }
            SolicitudExterna.Tipo.ELEGIR_CARPETA -> textoModo.text = "Carpeta elegida:\n$destino"
            SolicitudExterna.Tipo.CREAR_DOCUMENTO -> textoModo.text = "Guardar en:\n$destino"
            SolicitudExterna.Tipo.RECIBIR -> {
                val que = when {
                    s.fuentes.size > 1 -> "Guardar ${s.fuentes.size} archivos"
                    s.fuentes.size == 1 -> "Guardar «${s.fuentes[0].nombre}»"
                    else -> "Guardar el texto compartido"
                }
                textoModo.text = "$que en:\n$destino"
            }
            SolicitudExterna.Tipo.ABRIR -> Unit
        }
    }

    private fun banderasLectura(escritura: Boolean): Int {
        var b = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (escritura) b = b or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return b
    }

    private fun entregarElegidos(items: List<ArchivoItem>) {
        val archivos = items.filter { !it.esCarpeta }
        if (archivos.isEmpty()) {
            toast("Elige al menos un archivo")
            return
        }
        val ctx = requireContext()
        val uris = archivos.map { ProveedorDocumentos.uriDocumento(ctx, it.archivo) }
        val clip = ClipData.newRawUri("archivos", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val resultado = Intent()
        resultado.data = uris[0]
        resultado.clipData = clip
        resultado.addFlags(banderasLectura(solicitud?.pideEscritura == true))
        anfitrion()?.terminarConResultado(resultado)
    }

    private fun entregarCarpeta() {
        val carpeta = carpetaActual ?: return
        if (!carpeta.isDirectory || !carpeta.canRead()) {
            toast("No se puede acceder a esta carpeta", true)
            return
        }
        val resultado = Intent()
        resultado.data = ProveedorDocumentos.uriArbol(requireContext(), carpeta)
        resultado.addFlags(
            banderasLectura(true) or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        anfitrion()?.terminarConResultado(resultado)
    }

    private fun nombreValido(texto: String): String =
        texto.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_")

    /** CREATE_DOCUMENT: crea el archivo vacío con el nombre elegido y se lo entrega a la otra app para que escriba. */
    private fun guardarNuevoDocumento() {
        val carpeta = carpetaActual ?: run {
            toast("No hay carpeta seleccionada")
            return
        }
        val nombre = nombreValido(entradaNombreModo.text.toString())
        if (nombre.isEmpty()) {
            toast("Escribe un nombre para el archivo")
            return
        }
        if (!carpeta.canWrite()) {
            toast("No se puede escribir en esta carpeta", true)
            return
        }
        val destino = GestorArchivos.nombreLibre(carpeta, nombre)
        val creado = try { destino.createNewFile() } catch (_: Exception) { false }
        if (!creado) {
            toast("No se pudo crear el archivo", true)
            return
        }
        ajustes.ultimaCarpetaGuardado = carpeta.absolutePath
        val resultado = Intent()
        resultado.data = ProveedorDocumentos.uriDocumento(requireContext(), destino)
        resultado.addFlags(banderasLectura(true))
        anfitrion()?.terminarConResultado(resultado)
    }

    /** Compartir hacia la app / «Guardar en el dispositivo»: copia lo recibido a la carpeta actual. */
    private fun guardarRecibidos() {
        val s = solicitud ?: return
        val carpeta = carpetaActual ?: run {
            toast("No hay carpeta seleccionada")
            return
        }
        if (!carpeta.canWrite()) {
            toast("No se puede escribir en esta carpeta", true)
            return
        }
        val editable = entradaNombreModo.visibility == View.VISIBLE
        val nombreEditado = if (editable) nombreValido(entradaNombreModo.text.toString()) else null
        if (editable && nombreEditado.isNullOrEmpty()) {
            toast("Escribe un nombre para el archivo")
            return
        }
        val ctx = requireContext()
        val resolver = ctx.contentResolver
        val appCtx = ctx.applicationContext
        val total = s.fuentes.size.coerceAtLeast(1)
        ejecutarConProgreso(
            "Guardando…",
            { dlg ->
                val creados = ArrayList<String>()
                var fallos = 0
                if (s.fuentes.isEmpty()) {
                    val destino = GestorArchivos.nombreLibre(carpeta, nombreEditado ?: "texto.txt")
                    dlg.actualizar(destino.name)
                    try {
                        destino.writeText(s.textoRecibido ?: "")
                        creados.add(destino.absolutePath)
                    } catch (_: Exception) {
                        destino.delete()
                        fallos++
                    }
                } else {
                    for (fuente in s.fuentes) {
                        val nombre = if (s.fuentes.size == 1 && nombreEditado != null) nombreEditado else fuente.nombre
                        dlg.actualizar(nombre)
                        val destino = GestorArchivos.nombreLibre(carpeta, nombre)
                        try {
                            val entrada = resolver.openInputStream(fuente.uri)
                                ?: throw java.io.IOException("Sin datos")
                            entrada.use { i -> destino.outputStream().use { o -> i.copyTo(o) } }
                            creados.add(destino.absolutePath)
                        } catch (_: Exception) {
                            destino.delete()
                            fallos++
                        }
                    }
                }
                Pair(creados, fallos)
            }
        ) { r ->
            val (creados, fallos) = r.getOrDefault(Pair(ArrayList<String>(), total))
            if (creados.isNotEmpty()) {
                // Para que galería, descargas y otras apps vean los archivos nuevos al momento
                MediaScannerConnection.scanFile(appCtx, creados.toTypedArray(), null, null)
                ajustes.ultimaCarpetaGuardado = carpeta.absolutePath
            }
            ResumenCarpetas.invalidar()
            toast(
                "${creados.size} guardado(s) en «${carpeta.name.ifEmpty { "Interno" }}»" +
                    if (fallos > 0) ", $fallos error(es)" else "",
                fallos > 0
            )
            if (fallos == 0) anfitrion()?.terminar() else refrescar()
        }
    }

    /** ABRIR desde otra app: abre el archivo la primera vez que se muestra la pantalla. */
    private fun abrirPendiente() {
        val ruta = archivoPendiente ?: return
        archivoPendiente = null
        val f = File(ruta)
        if (f.isFile) abridor.abrir(ArchivoItem.desde(f), emptyList())
    }

    // ------------------------------------------------------------------ operaciones por lotes

    private fun iniciarOperacionLote(seleccion: List<ArchivoItem>, esMover: Boolean) {
        val archivos = seleccion.map { it.archivo }
        val modo = if (esMover) PortapapelesInterno.Modo.MOVER else PortapapelesInterno.Modo.COPIAR
        PortapapelesInterno.establecer(modo, archivos, "${seleccion.size} elemento(s)")
        toast("Navega a la carpeta destino y pulsa el botón de pegar (${seleccion.size}). Mantén pulsado ese botón para cancelar.", true)
        adaptador.limpiarSeleccion()
        actualizarEstadoFabPegar()
    }

    private fun pegarAqui() {
        val destino = carpetaActual ?: run {
            toast("Sin carpeta destino")
            return
        }
        val modo = PortapapelesInterno.modo ?: return
        val archivos = PortapapelesInterno.archivos
        ejecutarConProgreso(
            if (modo == PortapapelesInterno.Modo.MOVER) "Moviendo…" else "Copiando…",
            { dlg ->
                var ok = 0
                var fallos = 0
                for (f in archivos) {
                    dlg.actualizar(f.name)
                    val exito = if (modo == PortapapelesInterno.Modo.MOVER) {
                        val nuevo = GestorArchivos.moverA(f, destino)
                        if (nuevo != null && nuevo.absolutePath != f.absolutePath) {
                            repositorioFavoritos.actualizarRuta(f.absolutePath, nuevo.absolutePath)
                        }
                        nuevo != null
                    } else GestorArchivos.copiar(f, destino)
                    if (exito) ok++ else fallos++
                }
                Pair(ok, fallos)
            }
        ) { r ->
            val (ok, fallos) = r.getOrDefault(Pair(0, archivos.size))
            toast(
                "$ok elemento(s) pegado(s)" + if (fallos > 0) ", $fallos error(es) (no se puede pegar una carpeta dentro de sí misma)" else "",
                fallos > 0
            )
            PortapapelesInterno.vaciar()
            actualizarEstadoFabPegar()
            ResumenCarpetas.invalidar()
            refrescar()
        }
    }

    private fun actualizarEstadoFabPegar() {
        if (!::fabPegar.isInitialized) return
        fabPegar.visibility = if (PortapapelesInterno.hayContenido && !enModoExterno) View.VISIBLE else View.GONE
    }

    private fun compartirLote(seleccion: List<ArchivoItem>) {
        if (seleccion.isEmpty()) return
        val soloArchivos = seleccion.filter { !it.esCarpeta }
        if (soloArchivos.isEmpty()) {
            toast("Las carpetas no se pueden compartir directamente: comprímelas en ZIP primero", true)
            return
        }
        if (soloArchivos.size < seleccion.size) toast("Se omiten las carpetas (puedes comprimirlas en ZIP)", true)
        if (GestorArchivos.compartir(requireContext(), soloArchivos.map { it.archivo })) {
            adaptador.limpiarSeleccion()
        } else {
            toast("No se pudo compartir")
        }
    }

    private fun confirmarBorrar(items: List<ArchivoItem>) {
        val dentroPapelera = items.all { Papelera.esRutaPapelera(it.archivo) }
        val titulo = if (items.size == 1) "¿Borrar «${items[0].nombre}»?" else "¿Borrar ${items.size} elementos?"
        val b = AlertDialog.Builder(requireContext()).setTitle(titulo)
        if (dentroPapelera) {
            b.setMessage("Se borrará definitivamente. Esta acción no se puede deshacer.")
                .setPositiveButton("Borrar definitivamente") { _, _ -> borrar(items, false) }
        } else {
            b.setMessage("Se moverá a la papelera (menú ⋮ › Papelera) y se eliminará automáticamente a los 30 días.")
                .setPositiveButton("A la papelera") { _, _ -> borrar(items, true) }
                .setNeutralButton("Borrar definitivamente") { _, _ -> borrar(items, false) }
        }
        b.setNegativeButton("Cancelar", null).show()
    }

    private fun borrar(items: List<ArchivoItem>, aPapelera: Boolean) {
        ejecutarConProgreso(
            if (aPapelera) "Moviendo a la papelera…" else "Borrando…",
            { dlg ->
                var ok = 0
                var fallos = 0
                for (item in items) {
                    dlg.actualizar(item.nombre)
                    val r = if (aPapelera) papelera.enviar(item.archivo) else GestorArchivos.borrarDefinitivo(item.archivo)
                    if (r) ok++ else fallos++
                }
                Pair(ok, fallos)
            }
        ) { r ->
            val (ok, fallos) = r.getOrDefault(Pair(0, items.size))
            toast(
                (if (aPapelera) "$ok movido(s) a la papelera" else "$ok borrado(s)") +
                    if (fallos > 0) ", $fallos error(es)" else "",
                fallos > 0
            )
            adaptador.limpiarSeleccion()
            ResumenCarpetas.invalidar()
            refrescar()
        }
    }

    // ------------------------------------------------------------------ ZIP

    private fun comprimirSeleccion(items: List<ArchivoItem>) {
        val carpeta = carpetaActual ?: return
        val sugerido = if (items.size == 1) {
            val i = items[0]
            (if (i.esCarpeta) i.nombre else i.archivo.nameWithoutExtension.ifEmpty { i.nombre }) + ".zip"
        } else "Archivo.zip"
        pedirNombre("Comprimir en ZIP", sugerido) { nombre ->
            val nombreZip = if (nombre.lowercase().endsWith(".zip")) nombre else "$nombre.zip"
            val destino = GestorArchivos.nombreLibre(carpeta, nombreZip)
            val archivos = items.map { it.archivo }
            ejecutarConProgreso(
                "Comprimiendo…",
                { dlg -> Compresor.comprimir(archivos, destino) { dlg.actualizar(it) } }
            ) { r ->
                adaptador.limpiarSeleccion()
                if (r.getOrDefault(false)) toast("Creado ${destino.name}") else toast("No se pudo comprimir", true)
                ResumenCarpetas.invalidar()
                refrescar()
            }
        }
    }

    private fun extraerZip(item: ArchivoItem) {
        val temporal = ResolvedorUri.esTemporal(requireContext(), item.archivo)
        val padre = (if (temporal) carpetaActual ?: Almacenamientos.descargas() else item.archivo.parentFile) ?: return
        val base = if (item.nombre.lowercase().endsWith(".zip")) item.nombre.dropLast(4) else item.nombre
        val destino = GestorArchivos.nombreLibre(padre, base.ifEmpty { "extraido" })
        ejecutarConProgreso(
            "Extrayendo…",
            { dlg ->
                val ok = Compresor.extraer(item.archivo, destino) { dlg.actualizar(it) }
                if (!ok) destino.deleteRecursively()
                ok
            }
        ) { r ->
            adaptador.limpiarSeleccion()
            if (r.getOrDefault(false)) toast("Extraído en «${destino.name}»") else toast("No se pudo extraer el ZIP", true)
            ResumenCarpetas.invalidar()
            refrescar()
        }
    }

    // ------------------------------------------------------------------ varios

    private fun mostrarInfo(item: ArchivoItem) {
        fun mensaje(tamano: String) =
            "Tipo: ${if (item.esCarpeta) "Carpeta" else (item.mime ?: "desconocido")}\n" +
                "Tamaño: $tamano\n" +
                "Modificado: ${Formato.fecha(item.ultimaModificacion)}\n" +
                "Ruta:\n${item.archivo.absolutePath}"

        val dialogo = AlertDialog.Builder(requireContext())
            .setTitle(item.nombre)
            .setMessage(mensaje(if (item.esCarpeta) "calculando…" else item.tamanoLegible))
            .setPositiveButton("Cerrar", null)
            .show()
        if (item.esCarpeta) {
            viewLifecycleOwner.lifecycleScope.launch {
                val (bytes, n) = withContext(Dispatchers.IO) { GestorArchivos.resumenCarpeta(item.archivo) }
                if (dialogo.isShowing) dialogo.setMessage(mensaje("${Formato.tamano(bytes)} · $n archivo(s)"))
            }
        }
    }

    private fun pedirNombre(titulo: String, valorInicial: String, alAceptar: (String) -> Unit) {
        val vista = layoutInflater.inflate(R.layout.dialog_nombre, null)
        val entrada = vista.findViewById<EditText>(R.id.entradaNombre)
        entrada.setText(valorInicial)
        entrada.setSelection(entrada.text.length)

        AlertDialog.Builder(requireContext())
            .setTitle(titulo)
            .setView(vista)
            .setPositiveButton("Aceptar") { _, _ ->
                val nombre = entrada.text.toString().trim()
                if (nombre.isNotEmpty()) alAceptar(nombre)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    companion object {
        const val ARG_RUTA_INICIAL = "ruta_inicial"

        /** Ruta de un archivo que se abre automáticamente al mostrarse la pantalla (ABRIR desde otra app). */
        const val ARG_ABRIR = "abrir_archivo"
        private val CHIPS_CATEGORIA = listOf(
            R.id.chipImagenes, R.id.chipVideos, R.id.chipAudio,
            R.id.chipDocumentos, R.id.chipComprimidos, R.id.chipOtros
        )
        private const val ORDEN_NOMBRE = "nombre"
        private const val ORDEN_FECHA = "fecha"
        private const val ORDEN_TAMANO = "tamano"
        private const val ORDEN_TIPO = "tipo"
    }
}
