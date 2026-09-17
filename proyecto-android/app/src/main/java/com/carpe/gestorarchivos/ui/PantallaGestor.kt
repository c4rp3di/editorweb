package com.carpe.gestorarchivos.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.documentfile.provider.DocumentFile
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PantallaGestor : Fragment() {

    private var carpetaActual: DocumentFile? = null
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

    private var pendienteMover: DocumentFile? = null
    private var pendienteCopiar: DocumentFile? = null

    private val lanzadorCarpeta = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { abrirCarpetaDesdeUri(it, persistir = true) } }

    private val lanzadorElegirDestino = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { manejarDestinoElegido(it) } }

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
            lanzadorCarpeta.launch(null)
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

        val ultima = repositorio.leerUltimaCarpeta()
        if (ultima != null) {
            try {
                val tree = DocumentFile.fromTreeUri(requireContext(), ultima)
                if (tree != null && tree.exists() && tree.canRead()) carpetaActual = tree
            } catch (_: Exception) {}
        }
        refrescar()
        actualizarEstadoFabPegar()
        actualizarIconoFavorito()
        return vista
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

    private fun abrirCarpetaDesdeUri(uri: Uri, persistir: Boolean) {
        if (persistir) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            repositorio.guardarUltimaCarpeta(uri)
        }
        carpetaActual = DocumentFile.fromTreeUri(requireContext(), uri)
        entradaBusqueda.setText("")
        refrescar()
        actualizarIconoFavorito()
    }

    private fun abrirItem(item: ArchivoItem) {
        val doc = DocumentFile.fromSingleUri(requireContext(), item.uri) ?: return
        if (doc.isDirectory) {
            carpetaActual = doc
            entradaBusqueda.setText("")
            refrescar()
            actualizarIconoFavorito()
            return
        }
        if (esTexto(doc)) { abrirEditorTexto(doc); return }
        abrirConAppExterna(doc)
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
        val carpeta = carpetaActual
        if (carpeta == null || !carpeta.exists() || !carpeta.canRead()) {
            todosLosItems = emptyList()
            adaptador.actualizar(emptyList())
            textoRuta.text = getString(R.string.sin_carpeta)
            estadoVacio.visibility = View.VISIBLE
            iconoVacio.text = "📂"
            textoVacio.text = getString(R.string.elige_carpeta)
            return
        }
        todosLosItems = GestorArchivos.listar(carpeta)
        aplicarFiltro()
        textoRuta.text = "📂 " + rutaLegible(carpeta)
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

        val vacioReal = carpetaActual == null
        estadoVacio.visibility = if (ordenados.isEmpty()) View.VISIBLE else View.GONE
        iconoVacio.text = when {
            vacioReal -> "📂"
            busqueda.isNotEmpty() -> "🔍"
            else -> "📁"
        }
        textoVacio.text = when {
            vacioReal -> getString(R.string.elige_carpeta)
            busqueda.isNotEmpty() -> "Sin resultados para «$busqueda»"
            else -> getString(R.string.carpeta_vacia)
        }
    }

    private fun buscarRecursivo(carpeta: DocumentFile?, termino: String): List<ArchivoItem> {
        if (carpeta == null) return emptyList()
        val resultados = mutableListOf<ArchivoItem>()
        val cola = ArrayDeque<DocumentFile>()
        cola.add(carpeta)
        var iteraciones = 0
        val maxIteraciones = 5000
        while (cola.isNotEmpty() && iteraciones < maxIteraciones) {
            val actual = cola.removeFirst()
            iteraciones++
            actual.listFiles().forEach { doc ->
                if (doc.isDirectory) cola.add(doc)
                val nombre = doc.name ?: return@forEach
                if (nombre.contains(termino, ignoreCase = true)) {
                    resultados.add(
                        ArchivoItem(
                            nombre = nombre,
                            esCarpeta = doc.isDirectory,
                            uri = doc.uri,
                            tamano = if (doc.isFile) doc.length() else 0,
                            mime = doc.type,
                            ultimaModificacion = doc.lastModified()
                        )
                    )
                }
            }
        }
        return resultados
    }

    private fun rutaLegible(doc: DocumentFile): String {
        val nombre = doc.name ?: "(raíz)"
        val padre = doc.parentFile?.name
        return if (padre.isNullOrEmpty()) nombre else "$padre / $nombre"
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
            Toast.makeText(requireContext(), "Elige una carpeta primero", Toast.LENGTH_SHORT).show()
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
                        val nuevo = GestorArchivos.crearArchivo(carpeta, nombreFinal, "text/plain")
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
        val doc = DocumentFile.fromSingleUri(requireContext(), item.uri) ?: return
        val menu = PopupMenu(requireContext(), ancla)
        menu.inflate(R.menu.menu_contextual_archivo)

        if (!esTexto(doc)) menu.menu.findItem(R.id.accionEditar).isVisible = false
        if (doc.isDirectory) menu.menu.findItem(R.id.accionAbrir).isVisible = false

        menu.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                R.id.accionAbrir -> abrirItem(item)
                R.id.accionEditar -> abrirEditorTexto(doc)
                R.id.accionRenombrar -> pedirNombre("Renombrar", doc.name ?: "") { nuevo ->
                    if (GestorArchivos.renombrar(doc, nuevo)) refrescar()
                    else Toast.makeText(requireContext(), "No se pudo renombrar", Toast.LENGTH_SHORT).show()
                }
                R.id.accionMover -> {
                    pendienteMover = doc
                    lanzadorElegirDestino.launch(null)
                }
                R.id.accionCopiar -> {
                    pendienteCopiar = doc
                    lanzadorElegirDestino.launch(null)
                }
                R.id.accionCompartir -> GestorArchivos.compartir(requireContext(), doc)
                R.id.accionInfo -> mostrarInfo(doc)
                R.id.accionBorrar -> confirmarBorrar(doc)
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
                    val uri = favoritos[i].uri
                    try {
                        val tree = DocumentFile.fromTreeUri(requireContext(), uri)
                        if (tree != null && tree.exists() && tree.canRead()) {
                            carpetaActual = tree
                            entradaBusqueda.setText("")
                            refrescar()
                            actualizarIconoFavorito()
                        } else {
                            Toast.makeText(requireContext(), "Ya no hay acceso a esa carpeta", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), "Error al abrir favorito", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val actual = carpetaActual ?: return@setItems
                    val uri = actual.uri
                    val añadido = repositorioFavoritos.alternar(uri, actual.name ?: "Carpeta")
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
        val uri = carpetaActual?.uri ?: return
        val esFav = repositorioFavoritos.esFavorito(uri)
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
        val uris = seleccion.map { it.uri }
        val modo = if (esMover) PortapapelesInterno.Modo.MOVER else PortapapelesInterno.Modo.COPIAR
        PortapapelesInterno.establecer(modo, uris, "${seleccion.size} elemento(s)")
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
        val uris = PortapapelesInterno.uris
        var ok = 0; var fail = 0
        uris.forEach { u ->
            val doc = DocumentFile.fromSingleUri(requireContext(), u) ?: run { fail++; return@forEach }
            val exito = if (modo == PortapapelesInterno.Modo.MOVER)
                GestorArchivos.mover(requireContext(), doc, destino)
            else GestorArchivos.copiar(requireContext(), doc, destino)
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
            val doc = DocumentFile.fromSingleUri(requireContext(), seleccion[0].uri) ?: return
            GestorArchivos.compartir(requireContext(), doc)
            return
        }
        val uris = seleccion.map { it.uri }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir ${seleccion.size} archivos"))
    }

    private fun confirmarBorrarLote(seleccion: List<ArchivoItem>) {
        AlertDialog.Builder(requireContext())
            .setTitle("¿Borrar ${seleccion.size} elemento(s)?")
            .setMessage("Esta acción no se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                var ok = 0; var fail = 0
                seleccion.forEach { item ->
                    val doc = DocumentFile.fromSingleUri(requireContext(), item.uri)
                    if (doc != null && GestorArchivos.borrar(doc)) ok++ else fail++
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

    private fun manejarDestinoElegido(uri: Uri) {
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val destino = DocumentFile.fromTreeUri(requireContext(), uri)
        val origenPendiente = pendienteMover ?: pendienteCopiar
        val eraMover = pendienteMover != null
        pendienteMover = null; pendienteCopiar = null
        origenPendiente ?: return
        destino ?: return

        val ok = if (eraMover) GestorArchivos.mover(requireContext(), origenPendiente, destino)
                 else GestorArchivos.copiar(requireContext(), origenPendiente, destino)
        Toast.makeText(
            requireContext(),
            if (ok) (if (eraMover) "Movido correctamente" else "Copiado correctamente")
            else "No se pudo completar la operación",
            Toast.LENGTH_SHORT
        ).show()
        refrescar()
    }

    private fun mostrarInfo(doc: DocumentFile) {
        val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(Date(doc.lastModified()))
        val tamano = if (doc.isDirectory) "—"
                     else ArchivoItem(doc.name ?: "", false, doc.uri, doc.length()).tamanoLegible
        AlertDialog.Builder(requireContext())
            .setTitle(doc.name ?: "(sin nombre)")
            .setMessage(
                "Tipo: ${if (doc.isDirectory) "Carpeta" else (doc.type ?: "desconocido")}\n" +
                "Tamaño: $tamano\n" +
                "Modificado: $fecha\n" +
                "URI:\n${doc.uri}"
            )
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun confirmarBorrar(doc: DocumentFile) {
        AlertDialog.Builder(requireContext())
            .setTitle("¿Borrar?")
            .setMessage("Se borrará \"${doc.name}\"${if (doc.isDirectory) " y todo su contenido" else ""}.")
            .setPositiveButton("Borrar") { _, _ ->
                if (GestorArchivos.borrar(doc)) refrescar()
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

    private fun esTexto(doc: DocumentFile): Boolean {
        val mime = doc.type ?: ""
        if (mime.startsWith("text/")) return true
        val ext = (doc.name ?: "").substringAfterLast('.', "").lowercase()
        return ext in listOf(
            "txt","md","log","json","xml","csv","html","htm","css","js",
            "kt","java","py","sh","yml","yaml","ini","conf","properties"
        )
    }

    private fun abrirEditorTexto(doc: DocumentFile) {
        val contenido = GestorArchivos.leerTexto(requireContext(), doc)
        if (contenido == null) {
            Toast.makeText(requireContext(), "No se pudo leer el archivo", Toast.LENGTH_SHORT).show()
            return
        }
        val editor = PantallaEditorTexto.nueva(doc.uri, doc.name ?: "archivo.txt", contenido)
        parentFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, editor)
            .addToBackStack("editor")
            .commit()
    }

    private fun abrirConAppExterna(doc: DocumentFile) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(doc.uri, doc.type ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) }
        catch (e: Exception) {
            Toast.makeText(requireContext(), "No hay app para abrir este tipo de archivo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
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