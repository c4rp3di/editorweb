package com.carpe.gestorarchivos.ui

import android.content.Intent
import android.net.Uri
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
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
import com.carpe.gestorarchivos.data.Formato
import com.carpe.gestorarchivos.data.GestorArchivos
import com.carpe.gestorarchivos.data.Papelera
import com.carpe.gestorarchivos.data.ResumenCarpetas
import com.carpe.gestorarchivos.data.Permisos
import com.carpe.gestorarchivos.data.PortapapelesInterno
import com.carpe.gestorarchivos.data.RepositorioFavoritos
import com.carpe.gestorarchivos.data.RepositorioRecientes
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

    private var filtro = Filtro.TODOS
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
        adaptador.alPulsarMas = { _, ancla -> mostrarMenuSeleccion(ancla) }
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
            filtro = when (ids.firstOrNull()) {
                R.id.chipImagenes -> Filtro.IMAGENES
                R.id.chipVideos -> Filtro.VIDEOS
                R.id.chipAudio -> Filtro.AUDIO
                R.id.chipDocumentos -> Filtro.DOCUMENTOS
                R.id.chipComprimidos -> Filtro.COMPRIMIDOS
                else -> Filtro.TODOS
            }
            aplicarFiltro()
        }

        configurarBarraSeleccion(vista)

        if (carpetaActual == null && Permisos.tiene(ctx)) {
            val raiz = Environment.getExternalStorageDirectory()
            val ultima = repositorio.leerUltimaCarpeta()
            carpetaActual = if (ultima != null && ultima.exists() && ultima.canRead()) ultima else raiz
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
            carpetaActual = Environment.getExternalStorageDirectory()
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
        detenerObservador()
        trabajoListado?.cancel()
        trabajoBusqueda?.cancel()
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
            repositorio.guardarUltimaCarpeta(carpeta)
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
            indicadorBusqueda.visibility = View.VISIBLE
            trabajoBusqueda = viewLifecycleOwner.lifecycleScope.launch {
                delay(250)
                val res = withContext(Dispatchers.IO) { buscarRecursivo(carpeta, termino, ocultos, f) }
                indicadorBusqueda.visibility = View.GONE
                if (res.truncado) toast("Mostrando solo los primeros ${res.items.size} resultados")
                mostrarLista(res.items)
            }
            return
        }
        val base = todosLosItems.filter {
            (ocultos || !it.nombre.startsWith(".")) &&
                filtro.admite(it) &&
                (busqueda.isEmpty() || it.nombre.contains(busqueda, ignoreCase = true))
        }
        mostrarLista(base)
    }

    private fun CoroutineScope.buscarRecursivo(
        carpeta: File?, termino: String, ocultos: Boolean, f: Filtro
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
                    if (f.admite(item)) {
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
            filtro != Filtro.TODOS -> "No hay archivos de este tipo en la carpeta"
            else -> getString(R.string.carpeta_vacia)
        }
        actualizarCallbackAtras()
    }

    private fun ordenar(items: List<ArchivoItem>): List<ArchivoItem> {
        val comparador: Comparator<ArchivoItem> = when (ajustes.orden) {
            ORDEN_FECHA -> compareBy { it.ultimaModificacion }
            ORDEN_TAMANO -> compareBy { it.tamano }
            ORDEN_TIPO -> compareBy<ArchivoItem> { it.categoria.ordinal }
                .thenBy { it.extension }
                .thenComparator { a, b -> compararNatural(a.nombre, b.nombre) }
            else -> Comparator { a, b -> compararNatural(a.nombre, b.nombre) }
        }
        val carpetas = items.filter { it.esCarpeta }.sortedWith(comparador)
        val archivos = items.filter { !it.esCarpeta }.sortedWith(comparador)
        return if (ajustes.ordenAscendente) carpetas + archivos else carpetas.reversed() + archivos.reversed()
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
        menu.menu.findItem(R.id.accionExtraer).isVisible = unico && item.extension == "zip"
        menu.menu.findItem(R.id.accionAbrirConOtra).isVisible = unico && !item.esCarpeta
        menu.menu.findItem(R.id.accionInfo).isVisible = unico
        menu.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                R.id.accionAbrir -> { adaptador.limpiarSeleccion(); abrirItem(item) }
                R.id.accionEditar -> { adaptador.limpiarSeleccion(); abrirEditorTexto(item.archivo) }
                R.id.accionRenombrar -> pedirNombre("Renombrar", item.nombre) { nuevo ->
                    if (nuevo == item.nombre) return@pedirNombre
                    if (GestorArchivos.renombrar(item.archivo, nuevo)) {
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
        menu.setOnMenuItemClickListener { m ->
            when (m.itemId) {
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

    private fun mostrarMenuFavoritos() {
        val favoritos = repositorioFavoritos.listar()
        val actual = carpetaActual
        val esFavActual = actual != null && repositorioFavoritos.esFavorito(actual.absolutePath)
        val opciones = mutableListOf<String>()
        favoritos.forEach { opciones.add("⭐ ${it.nombre}") }
        opciones.add(if (esFavActual) "➖ Quitar carpeta actual de favoritos" else "➕ Añadir carpeta actual a favoritos")

        AlertDialog.Builder(requireContext())
            .setTitle("Favoritos")
            .setItems(opciones.toTypedArray()) { _, i ->
                if (i < favoritos.size) {
                    val carpeta = File(favoritos[i].ruta)
                    if (carpeta.exists() && carpeta.canRead()) irA(carpeta)
                    else toast("Ya no existe esa carpeta")
                } else {
                    val c = carpetaActual ?: return@setItems
                    val anadido = repositorioFavoritos.alternar(c.absolutePath, c.name)
                    toast(if (anadido) "Añadido a favoritos" else "Quitado de favoritos")
                    actualizarIconoFavorito()
                }
            }
            .show()
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
                    val exito = if (modo == PortapapelesInterno.Modo.MOVER) GestorArchivos.mover(f, destino)
                    else GestorArchivos.copiar(f, destino)
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
        fabPegar.visibility = if (PortapapelesInterno.hayContenido) View.VISIBLE else View.GONE
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
        val padre = item.archivo.parentFile ?: return
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
        private const val ORDEN_NOMBRE = "nombre"
        private const val ORDEN_FECHA = "fecha"
        private const val ORDEN_TAMANO = "tamano"
        private const val ORDEN_TIPO = "tipo"
    }
}
