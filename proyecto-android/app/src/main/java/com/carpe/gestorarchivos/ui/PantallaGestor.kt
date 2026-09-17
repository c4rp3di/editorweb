package com.carpe.gestorarchivos.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.AdaptadorArchivos
import com.carpe.gestorarchivos.data.ArchivoItem
import com.carpe.gestorarchivos.data.GestorArchivos
import com.carpe.gestorarchivos.data.PortapapelesInterno
import com.carpe.gestorarchivos.data.RepositorioFavoritos
import com.carpe.gestorarchivos.data.RepositorioRecientes
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PantallaGestor : Fragment() {

    private var carpetaActual: File? = null
    private lateinit var adaptador: AdaptadorArchivos
    private lateinit var recycler: RecyclerView
    private lateinit var textoRuta: TextView
    private lateinit var estadoVacio: View
    private lateinit var textoVacio: TextView
    private lateinit var iconoVacio: TextView
    private lateinit var entradaBusqueda: EditText
    private lateinit var botonLimpiarBusqueda: ImageView
    private lateinit var botonBusquedaRecursiva: ImageView
    private lateinit var barraNormal: View
    private lateinit var barraSeleccion: View
    private lateinit var textoSeleccionados: TextView
    private lateinit var fabPegar: FloatingActionButton
    private lateinit var botonFavoritos: ImageView

    private lateinit var repositorio: RepositorioRecientes
    private lateinit var repositorioFavoritos: RepositorioFavoritos

    private var ordenActual = ORDEN_NOMBRE
    private var ordenAscendente = true
    private var busqueda = ""
    private var busquedaRecursiva = false
    private var todosLosItems: List<ArchivoItem> = emptyList()

    private var pendienteMover: File? = null
    private var pendienteCopiar: File? = null

    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?
    ): View {
        val vista = inflater.inflate(R.layout.fragment_gestor, contenedor, false)
        repositorio = RepositorioRecientes(requireContext())
        repositorioFavoritos = RepositorioFavoritos(requireContext())

        recycler = vista.findViewById(R.id.listaArchivos)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        textoRuta = vista.findViewById(R.id.textoRuta)
        estadoVacio = vista.findViewById(R.id.estadoVacio)
        textoVacio = vista.findViewById(R.id.textoVacio)
        iconoVacio = vista.findViewById(R.id.iconoVacio)
        entradaBusqueda = vista.findViewById(R.id.entradaBusqueda)
        botonLimpiarBusqueda = vista.findViewById(R.id.botonLimpiarBusqueda)
        botonBusquedaRecursiva = vista.findViewById(R.id.botonBusquedaRecursiva)
        barraNormal = vista.findViewById(R.id.barraNormal)
        barraSeleccion = vista.findViewById(R.id.barraSeleccion)
        textoSeleccionados = vista.findViewById(R.id.textoSeleccionados)
        fabPegar = vista.findViewById(R.id.fabPegar)
        botonFavoritos = vista.findViewById(R.id.botonFavoritos)

        adaptador = AdaptadorArchivos(
            alTocar = { item -> abrirItem(item) },
            alMantener = { item, view -> mostrarMenuContextual(item, view) },
            alCambiarSeleccion = { sel -> actualizarBarraSeleccion(sel) }
        )
        recycler.adapter = adaptador

        vista.findViewById<View>(R.id.botonSubir).setOnClickListener { subirNivel() }
        vista.findViewById<View>(R.id.botonOrdenar).setOnClickListener { mostrarMenuOrden() }
        botonFavoritos.setOnClickListener { mostrarMenuFavoritos() }
        vista.findViewById<FloatingActionButton>(R.id.fabCarpeta).setOnClickListener {
            abrirAjustesPermisoOInicio()
        }
        vista.findViewById<FloatingActionButton>(R.id.fabCrear).setOnClickListener {
            mostrarMenuCrear()
        }
        fabPegar.setOnClickListener { pegarAqui() }

        botonBusquedaRecursiva.setOnClickListener {
            busquedaRecursiva = !busquedaRecursiva
            botonBusquedaRecursiva.setColorFilter(
                if (busquedaRecursiva) requireContext().getColor(R.color.primario)
                else requireContext().getColor(R.color.texto_secundario)
            )
            if (busqueda.isNotEmpty()) aplicarFiltro()
        }

        entradaBusqueda.doAfterTextChanged { texto ->
            busqueda = texto?.toString()?.trim() ?: ""
            botonLimpiarBusqueda.visibility = if (busqueda.isEmpty()) View.GONE else View.VISIBLE
            aplicarFiltro()
        }

        botonLimpiarBusqueda.setOnClickListener {
            entradaBusqueda.setText("")
            busqueda = ""
        }

        configurarBarraSeleccion(vista)

        // Arrancar en la raíz del almacenamiento interno
        if (tienePermisoArchivos()) {
            val raiz = Environment.getExternalStorageDirectory()
            val ultima = repositorio.leerUltimaCarpeta()
            val inicio = if (ultima != null && ultima.exists() && ultima.canRead()) ultima else raiz
            carpetaActual = inicio
        } else {
            carpetaActual = null
        }
        refrescar()
        actualizarEstadoFabPegar()
        actualizarIconoFavorito()
        return vista
    }

    private fun tienePermisoArchivos(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    private fun abrirAjustesPermisoOInicio() {
        if (tienePermisoArchivos()) {
            val raiz = Environment.getExternalStorageDirectory()
            carpetaActual = raiz
            entradaBusqueda.setText("")
            refrescar()
            actualizarIconoFavorito()
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.parse("package:" + requireContext().packageName)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun configurarBarraSeleccion(vista: View) {
        vista.findViewById<View>(R.id.botonCerrarSeleccion).setOnClickListener {
            adaptador.limpiarSeleccion()
        }
        vista.findViewById<View>(R.id.botonSelMover).setOnClickListener {
            val sel = adaptador.obtenerSeleccionados()
            if (sel.isEmpty()) return@setOnClickListener
            iniciarOperacionLote(sel, esMover = true)
        }
        vista.findViewById<View>(R.id.botonSelCopiar).setOnClickListener {
            val sel = adaptador.obtenerSeleccionados()
            if (sel.isEmpty()) return@setOnClickListener
            iniciarOperacionLote(sel, esMover = false)
        }
        vista.findViewById<View>(R.id.botonSelCompartir).setOnClickListener {
            val sel = adaptador.obtenerSeleccionados()
            compartirLote(sel)
            adaptador.limpiarSeleccion()
        }
        vista.findViewById<View>(R.id.botonSelBorrar).setOnClickListener {
            val sel = adaptador.obtenerSeleccionados()
            if (sel.isEmpty()) return@setOnClickListener
            confirmarBorrarLote(sel)
        }
    }

    private fun abrirItem(item: ArchivoItem) {
        if (item.esCarpeta) {
            carpetaActual = item.archivo
            entradaBusqueda.setText("")
            refrescar()
            actualizarIconoFavorito()
            return
        }
        if (esTexto(item)) {
            abrirEditorTexto(item.archivo)
            return
        }
        abrirConAppExterna(item.archivo)
    }

    private fun subirNivel() {
        val padre = carpetaActual?.parentFile ?: run {
            Toast.makeText(requireContext(), "Ya estás en la raíz", Toast.LENGTH_SHORT).show()
            return
        }
        carpetaActual = padre
        entradaBusqueda.setText("")
        refrescar()
        actualizarIconoFavorito()
    }

    private fun refrescar() {
        if (!tienePermisoArchivos()) {
            todosLosItems = emptyList()
            adaptador.actualizar(emptyList())
            textoRuta.text = "Sin permiso de archivos"
            estadoVacio.visibility = View.VISIBLE
            iconoVacio.text = "🔒"
            textoVacio.text = "Toca el botón morado para conceder acceso a los archivos"
            return
        }
        val carpeta = carpetaActual
        if (carpeta == null || !carpeta.exists() || !carpeta.canRead()) {
            todosLosItems = emptyList()
            adaptador.actualizar(emptyList())
            textoRuta.text = "Sin carpeta"
            estadoVacio.visibility = View.VISIBLE
            iconoVacio.text = "📂"
            textoVacio.text = "No se puede acceder a esta carpeta"
            return
        }
        todosLosItems = GestorArchivos.listar(carpeta)
        aplicarFiltro()
        textoRuta.text = "📂 " + carpeta.absolutePath
        repositorio.guardarUltimaCarpeta(carpeta)
    }

    private fun aplicarFiltro() {
        val base = if (busqueda.isEmpty()) {
            todosLosItems
        } else if (busquedaRecursiva) {
            buscarRecursivo(carpetaActual, busqueda)
        } else {
            todosLosItems.filter { it.nombre.contains(busqueda, ignoreCase = true) }
        }
        val ordenados = ordenar(base)
        adaptador.actualizar(ordenados)

        val vacioReal = carpetaActual == null || !tienePermisoArchivos()
        estadoVacio.visibility = if (ordenados.isEmpty()) View.VISIBLE else View.GONE
        iconoVacio.text = when {
            !tienePermisoArchivos() -> "🔒"
            vacioReal -> "📂"
            busqueda.isNotEmpty() -> "🔍"
            else -> "📁"
        }
        textoVacio.text = when {
            !tienePermisoArchivos() -> "Toca el botón morado para conceder acceso a los archivos"
            vacioReal -> "No hay carpeta seleccionada"
            busqueda.isNotEmpty() -> "Sin resultados para «$busqueda»"
            else -> getString(R.string.carpeta_vacia)
        }
    }

    private fun buscarRecursivo(carpeta: File?, termino: String): List<ArchivoItem> {
        if (carpeta == null) return emptyList()
        val resultados = mutableListOf<ArchivoItem>()
        val cola = ArrayDeque<File>()
        cola.add(carpeta)
        var iteraciones = 0
        val maxIteraciones = 5000
        while (cola.isNotEmpty() && iteraciones < maxIteraciones) {
            val actual = cola.removeFirst()
            iteraciones++
            actual.listFiles()?.forEach { hijo ->
                if (hijo.isDirectory) cola.add(hijo)
                if (hijo.name.contains(termino, ignoreCase = true)) {
                    resultados.add(ArchivoItem.desde(hijo))
                }
            }
        }
        return resultados
    }

    private fun ordenar(items: List<ArchivoItem>): List<ArchivoItem> {
        val comparador: Comparator<ArchivoItem> = when (ordenActual) {
            ORDEN_FECHA -> compareBy { it.ultimaModificacion }
            ORDEN_TAMANO -> compareBy { it.tamano }
            ORDEN_TIPO -> compareBy { it.mime ?: "" }
            else -> compareBy { it.nombre.lowercase() }
        }
        val base = items.sortedWith(
            compareByDescending<ArchivoItem> { it.esCarpeta }.then(comparador)
        )
        return if (ordenAscendente) base else base.reversed()
    }

    private fun actualizarBarraSeleccion(seleccionados: Set<String>) {
        val n = seleccionados.size
        if (n == 0) {
            barraSeleccion.visibility = View.GONE
            barraNormal.visibility = View.VISIBLE
        } else {
            barraSeleccion.visibility = View.VISIBLE
            barraNormal.visibility = View.GONE
            textoSeleccionados.text = getString(R.string.seleccionados, n)
        }
    }

    private fun mostrarMenuOrden() {
        val menu = PopupMenu(requireContext(), requireView().findViewById(R.id.botonOrdenar))
        menu.inflate(R.menu.menu_seleccion_orden)
        menu.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                R.id.ordenNombreAsc -> { ordenActual = ORDEN_NOMBRE; ordenAscendente = true }
                R.id.ordenNombreDesc -> { ordenActual = ORDEN_NOMBRE; ordenAscendente = false }
                R.id.ordenFechaDesc -> { ordenActual = ORDEN_FECHA; ordenAscendente = false }
                R.id.ordenFechaAsc -> { ordenActual = ORDEN_FECHA; ordenAscendente = true }
                R.id.ordenTamanoDesc -> { ordenActual = ORDEN_TAMANO; ordenAscendente = false }
                R.id.ordenTamanoAsc -> { ordenActual = ORDEN_TAMANO; ordenAscendente = true }
                R.id.ordenTipo -> { ordenActual = ORDEN_TIPO; ordenAscendente = true }
            }
            aplicarFiltro()
            true
        }
        menu.show()
    }

    private fun mostrarMenuCrear() {
        val carpeta = carpetaActual ?: run {
            Toast.makeText(requireContext(), "No hay carpeta seleccionada", Toast.LENGTH_SHORT).show()
            return
        }
        val opciones = arrayOf("📁 Nueva carpeta", "📝 Nuevo archivo de texto")
        AlertDialog.Builder(requireContext())
            .setTitle("Crear")
            .setItems(opciones) { _, i ->
                if (i == 0) {
                    pedirNombre("Nueva carpeta", "") { nombre ->
                        val creada = GestorArchivos.crearCarpeta(carpeta, nombre)
                        if (creada == null) Toast.makeText(requireContext(), "Ya existe o no se pudo crear", Toast.LENGTH_SHORT).show()
                        refrescar()
                    }
                } else {
                    pedirNombre("Nuevo archivo", "archivo.txt") { nombre ->
                        val nombreFinal = if (nombre.contains(".")) nombre else "$nombre.txt"
                        val nuevo = GestorArchivos.crearArchivo(carpeta, nombreFinal)
                        if (nuevo == null) {
                            Toast.makeText(requireContext(), "Ya existe o no se pudo crear", Toast.LENGTH_SHORT).show()
                        } else {
                            refrescar()
                            abrirEditorTexto(nuevo)
                        }
                    }
                }
            }
            .show()
    }

    private fun mostrarMenuContextual(item: ArchivoItem, ancla: View) {
        val menu = PopupMenu(requireContext(), ancla)
        menu.inflate(R.menu.menu_contextual_archivo)

        if (!esTexto(item)) menu.menu.findItem(R.id.accionEditar).isVisible = false
        if (item.esCarpeta) menu.menu.findItem(R.id.accionAbrir).isVisible = false

        menu.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                R.id.accionAbrir -> abrirItem(item)
                R.id.accionEditar -> abrirEditorTexto(item.archivo)
                R.id.accionRenombrar -> pedirNombre("Renombrar", item.nombre) { nuevo ->
                    if (GestorArchivos.renombrar(item.archivo, nuevo)) refrescar()
                    else Toast.makeText(requireContext(), "No se pudo renombrar", Toast.LENGTH_SHORT).show()
                }
                R.id.accionMover -> {
                    pendienteMover = item.archivo
                    Toast.makeText(requireContext(), "Navega a la carpeta destino y mantén pulsado en vacío para pegar", Toast.LENGTH_LONG).show()
                    PortapapelesInterno.establecer(PortapapelesInterno.Modo.MOVER, listOf(item.archivo), item.nombre)
                    actualizarEstadoFabPegar()
                }
                R.id.accionCopiar -> {
                    pendienteCopiar = item.archivo
                    Toast.makeText(requireContext(), "Navega a la carpeta destino y pulsa Pegar", Toast.LENGTH_LONG).show()
                    PortapapelesInterno.establecer(PortapapelesInterno.Modo.COPIAR, listOf(item.archivo), item.nombre)
                    actualizarEstadoFabPegar()
                }
                R.id.accionCompartir -> GestorArchivos.compartir(requireContext(), item.archivo)
                R.id.accionInfo -> mostrarInfo(item)
                R.id.accionBorrar -> confirmarBorrar(item)
            }
            true
        }
        menu.show()
    }

    private fun mostrarMenuFavoritos() {
        val favoritos = repositorioFavoritos.listar()
        val opciones = mutableListOf<String>()
        favoritos.forEach { opciones.add("⭐ ${it.nombre}") }
        opciones.add("➕ Añadir carpeta actual a favoritos")

        AlertDialog.Builder(requireContext())
            .setTitle("Favoritos")
            .setItems(opciones.toTypedArray()) { _, i ->
                if (i < favoritos.size) {
                    val ruta = favoritos[i].ruta
                    val carpeta = File(ruta)
                    if (carpeta.exists() && carpeta.canRead()) {
                        carpetaActual = carpeta
                        entradaBusqueda.setText("")
                        refrescar()
                        actualizarIconoFavorito()
                    } else {
                        Toast.makeText(requireContext(), "Ya no existe esa carpeta", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val actual = carpetaActual ?: return@setItems
                    val añadido = repositorioFavoritos.alternar(actual.absolutePath, actual.name)
                    Toast.makeText(
                        requireContext(),
                        if (añadido) "Añadido a favoritos" else "Quitado de favoritos",
                        Toast.LENGTH_SHORT
                    ).show()
                    actualizarIconoFavorito()
                }
            }
            .show()
    }

    private fun actualizarIconoFavorito() {
        val carpeta = carpetaActual ?: return
        val esFav = repositorioFavoritos.esFavorito(carpeta.absolutePath)
        botonFavoritos.setImageResource(
            if (esFav) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
        botonFavoritos.setColorFilter(
            if (esFav) requireContext().getColor(R.color.primario)
            else requireContext().getColor(R.color.texto)
        )
    }

    private fun iniciarOperacionLote(seleccion: List<ArchivoItem>, esMover: Boolean) {
        val archivos = seleccion.map { it.archivo }
        val modo = if (esMover) PortapapelesInterno.Modo.MOVER else PortapapelesInterno.Modo.COPIAR
        PortapapelesInterno.establecer(modo, archivos, "${seleccion.size} elemento(s)")
        Toast.makeText(
            requireContext(),
            "Navega a la carpeta destino y pulsa Pegar (${seleccion.size})",
            Toast.LENGTH_SHORT
        ).show()
        adaptador.limpiarSeleccion()
        actualizarEstadoFabPegar()
    }

    private fun pegarAqui() {
        val destino = carpetaActual ?: run {
            Toast.makeText(requireContext(), "Sin carpeta destino", Toast.LENGTH_SHORT).show()
            return
        }
        val modo = PortapapelesInterno.modo ?: return
        val archivos = PortapapelesInterno.archivos
        var ok = 0; var fail = 0
        archivos.forEach { f ->
            val exito = if (modo == PortapapelesInterno.Modo.MOVER)
                GestorArchivos.mover(f, destino)
            else GestorArchivos.copiar(f, destino)
            if (exito) ok++ else fail++
        }
        Toast.makeText(
            requireContext(),
            "$ok pegado(s)${if (fail > 0) ", $fail error(es)" else ""}",
            Toast.LENGTH_SHORT
        ).show()
        PortapapelesInterno.vaciar()
        actualizarEstadoFabPegar()
        refrescar()
    }

    private fun actualizarEstadoFabPegar() {
        fabPegar.visibility = if (PortapapelesInterno.hayContenido) View.VISIBLE else View.GONE
    }

    private fun compartirLote(seleccion: List<ArchivoItem>) {
        if (seleccion.isEmpty()) return
        if (seleccion.size == 1) {
            GestorArchivos.compartir(requireContext(), seleccion[0].archivo)
            return
        }
        try {
            val uris = ArrayList<Uri>()
            seleccion.forEach { item ->
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().packageName + ".fileprovider",
                    item.archivo
                )
                uris.add(uri)
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir ${seleccion.size} archivos"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No se pudo compartir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmarBorrarLote(seleccion: List<ArchivoItem>) {
        AlertDialog.Builder(requireContext())
            .setTitle("¿Borrar ${seleccion.size} elemento(s)?")
            .setMessage("Esta acción no se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                var ok = 0; var fail = 0
                seleccion.forEach { item ->
                    if (GestorArchivos.borrar(item.archivo)) ok++ else fail++
                }
                Toast.makeText(
                    requireContext(),
                    "$ok borrado(s)${if (fail > 0) ", $fail error(es)" else ""}",
                    Toast.LENGTH_SHORT
                ).show()
                adaptador.limpiarSeleccion()
                refrescar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarInfo(item: ArchivoItem) {
        val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(Date(item.ultimaModificacion))
        val tamano = if (item.esCarpeta) "—" else item.tamanoLegible
        AlertDialog.Builder(requireContext())
            .setTitle(item.nombre)
            .setMessage(
                "Tipo: ${if (item.esCarpeta) "Carpeta" else (item.mime ?: "desconocido")}\n" +
                "Tamaño: $tamano\n" +
                "Modificado: $fecha\n" +
                "Ruta:\n${item.archivo.absolutePath}"
            )
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun confirmarBorrar(item: ArchivoItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("¿Borrar?")
            .setMessage("Se borrará \"${item.nombre}\"${if (item.esCarpeta) " y todo su contenido" else ""}.")
            .setPositiveButton("Borrar") { _, _ ->
                if (GestorArchivos.borrar(item.archivo)) refrescar()
                else Toast.makeText(requireContext(), "No se pudo borrar", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

    private fun esTexto(item: ArchivoItem): Boolean {
        val mime = item.mime ?: ""
        if (mime.startsWith("text/")) return true
        val ext = item.nombre.substringAfterLast('.', "").lowercase()
        return ext in listOf(
            "txt","md","log","json","xml","csv","html","htm","css","js",
            "kt","java","py","sh","yml","yaml","ini","conf","properties"
        )
    }

    private fun abrirEditorTexto(archivo: File) {
        val contenido = GestorArchivos.leerTexto(archivo)
        if (contenido == null) {
            Toast.makeText(requireContext(), "No se pudo leer el archivo", Toast.LENGTH_SHORT).show()
            return
        }
        val editor = PantallaEditorTexto.nueva(archivo.absolutePath, archivo.name, contenido)
        parentFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, editor)
            .addToBackStack("editor")
            .commit()
    }

    private fun abrirConAppExterna(archivo: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".fileprovider",
                archivo
            )
            val item = ArchivoItem.desde(archivo)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No hay app para abrir este tipo de archivo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (tienePermisoArchivos() && carpetaActual == null) {
            carpetaActual = Environment.getExternalStorageDirectory()
        }
        refrescar()
        actualizarEstadoFabPegar()
        actualizarIconoFavorito()
    }

    companion object {
        private const val ORDEN_NOMBRE = "nombre"
        private const val ORDEN_FECHA = "fecha"
        private const val ORDEN_TAMANO = "tamano"
        private const val ORDEN_TIPO = "tipo"
    }
}