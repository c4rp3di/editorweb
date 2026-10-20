package com.carpe.gestorarchivos.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
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
import com.carpe.gestorarchivos.data.BuscadorMediaStore
import com.carpe.gestorarchivos.data.Filtro
import com.carpe.gestorarchivos.data.Papelera
import com.carpe.gestorarchivos.data.RepositorioRecientes
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Buscador global + historial. Con el campo vacío muestra «Abiertos en la app» y «Modificados hace poco».
 * Al escribir: resultados instantáneos del índice del sistema y, en paralelo, un recorrido de todos los almacenamientos.
 */
class PantallaBusqueda : Fragment() {

    companion object {
        const val REQ_CARPETA = "ir_a_carpeta"
        const val CLAVE_RUTA = "ruta"
        private const val ARG_FOCO = "foco"
        private const val MAX_RESULTADOS = 3000

        fun nueva(foco: Boolean) = PantallaBusqueda().apply {
            arguments = Bundle().apply { putBoolean(ARG_FOCO, foco) }
        }
    }

    private lateinit var adaptador: AdaptadorArchivos
    private lateinit var abridor: AbridorArchivos
    private lateinit var repositorio: RepositorioRecientes
    private lateinit var entrada: EditText
    private lateinit var spinner: ProgressBar
    private lateinit var botonLimpiar: View
    private lateinit var filaVista: View
    private lateinit var botonBorrar: TextView
    private lateinit var vacio: View
    private lateinit var iconoVacio: TextView
    private lateinit var textoVacio: TextView

    private var consulta = ""
    private var filtro = Filtro.TODOS
    private var verAbiertos = true
    private var trabajo: Job? = null

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_busqueda, contenedor, false)
        val ctx = requireContext()
        repositorio = RepositorioRecientes(ctx)
        entrada = v.findViewById(R.id.entradaBusquedaGlobal)
        spinner = v.findViewById(R.id.indicadorBusquedaGlobal)
        botonLimpiar = v.findViewById(R.id.botonLimpiarBusquedaGlobal)
        filaVista = v.findViewById(R.id.filaVista)
        botonBorrar = v.findViewById(R.id.botonBorrarHistorial)
        vacio = v.findViewById(R.id.estadoBusquedaVacio)
        iconoVacio = v.findViewById(R.id.iconoBusquedaVacio)
        textoVacio = v.findViewById(R.id.textoBusquedaVacio)

        abridor = AbridorArchivos(this, alIrACarpeta = { irAlGestorEn(it) })
        adaptador = AdaptadorArchivos(
            alTocar = { item -> abridor.abrir(item, adaptador.obtenerItems()) },
            alCambiarSeleccion = { }
        )
        adaptador.mostrarRutaPadre = true
        adaptador.alMantener = { item -> opcionesResultado(item) }
        val lista = v.findViewById<RecyclerView>(R.id.listaBusqueda)
        lista.layoutManager = LinearLayoutManager(ctx)
        lista.adapter = adaptador

        v.findViewById<View>(R.id.botonVolverBusqueda).setOnClickListener { volverAtras() }
        botonLimpiar.setOnClickListener { entrada.setText("") }
        entrada.doAfterTextChanged {
            consulta = it?.toString()?.trim() ?: ""
            actualizar()
        }
        entrada.setOnEditorActionListener { vista, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_SEARCH) {
                ocultarTeclado(vista)
                true
            } else false
        }

        v.findViewById<ChipGroup>(R.id.grupoFiltrosBusqueda).setOnCheckedStateChangeListener { _, ids ->
            filtro = when (ids.firstOrNull()) {
                R.id.chipBImagenes -> Filtro.IMAGENES
                R.id.chipBVideos -> Filtro.VIDEOS
                R.id.chipBAudio -> Filtro.AUDIO
                R.id.chipBDocumentos -> Filtro.DOCUMENTOS
                R.id.chipBComprimidos -> Filtro.COMPRIMIDOS
                else -> Filtro.TODOS
            }
            actualizar()
        }
        v.findViewById<ChipGroup>(R.id.grupoVistaBusqueda).setOnCheckedStateChangeListener { _, ids ->
            verAbiertos = ids.firstOrNull() != R.id.chipNuevos
            actualizar()
        }
        botonBorrar.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("¿Borrar el historial?")
                .setMessage("Solo se borra la lista de archivos abiertos; los archivos no se tocan.")
                .setPositiveButton("Borrar") { _, _ ->
                    repositorio.borrarArchivosRecientes()
                    actualizar()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (arguments?.getBoolean(ARG_FOCO) == true) {
            entrada.post {
                entrada.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(entrada, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        actualizar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        trabajo?.cancel()
    }

    private fun ocultarTeclado(v: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun irAlGestorEn(carpeta: File) {
        parentFragmentManager.setFragmentResult(REQ_CARPETA, bundleOf(CLAVE_RUTA to carpeta.absolutePath))
        volverAtras()
    }

    private fun opcionesResultado(item: ArchivoItem) {
        val padre = item.archivo.parentFile
        val opciones = ArrayList<String>()
        if (padre != null) opciones.add("📂 Mostrar en su carpeta")
        if (!item.esCarpeta) opciones.add("Abrir con…")
        if (opciones.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(item.nombre)
            .setItems(opciones.toTypedArray()) { _, i ->
                val texto = opciones[i]
                if (texto.startsWith("📂") && padre != null) irAlGestorEn(padre)
                else abridor.abrirCon(item)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun actualizar() {
        if (view == null) return
        trabajo?.cancel()
        spinner.visibility = View.GONE
        val sinTexto = consulta.isEmpty()
        filaVista.visibility = if (sinTexto) View.VISIBLE else View.GONE
        botonLimpiar.visibility = if (sinTexto) View.GONE else View.VISIBLE
        if (sinTexto) cargarVista() else buscar()
    }

    private fun mostrar(lista: List<ArchivoItem>, textoSinResultados: String, icono: String) {
        adaptador.actualizar(lista)
        vacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
        iconoVacio.text = icono
        textoVacio.text = textoSinResultados
    }

    // ---------------------------------------------------------------- historial

    private fun cargarVista() {
        val ctx = requireContext()
        val f = filtro
        val abiertos = verAbiertos
        botonBorrar.visibility = View.GONE
        trabajo = viewLifecycleOwner.lifecycleScope.launch {
            val lista = withContext(Dispatchers.IO) {
                if (abiertos) {
                    repositorio.archivosRecientes()
                        .filter { it.exists() }
                        .map { ArchivoItem.desde(it) }
                        .filter { f.admite(it) }
                } else {
                    BuscadorMediaStore.modificadosRecientemente(ctx, 150).filter { f.admite(it) }
                }
            }
            mostrar(
                lista,
                if (abiertos) "Aún no has abierto archivos desde la app" else "No hay archivos recientes de este tipo",
                if (abiertos) "🕘" else "🆕"
            )
            botonBorrar.visibility = if (abiertos && lista.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------- búsqueda

    private fun buscar() {
        val ctx = requireContext()
        val q = consulta
        val f = filtro
        val ocultos = Ajustes(ctx).mostrarOcultos
        spinner.visibility = View.VISIBLE
        vacio.visibility = View.GONE
        adaptador.actualizar(emptyList())
        trabajo = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            val vistas = HashSet<String>()
            val lista = ArrayList<ArchivoItem>()

            // 1) Resultados instantáneos del índice del sistema
            val rapidos = withContext(Dispatchers.IO) { BuscadorMediaStore.buscarPorNombre(ctx, q, 300) }
            for (item in rapidos) {
                if (!ocultos && item.archivo.absolutePath.contains("/.")) continue
                if (f.admite(item) && vistas.add(item.clave)) lista.add(item)
            }
            if (lista.isNotEmpty()) publicar(lista, q)

            // 2) Recorrido de todos los almacenamientos, por lotes, publicando según aparecen resultados
            val raices = Almacenamientos.volumenes(ctx).map { it.raiz }
            val rutasRaiz = raices.map { it.absolutePath }.toSet()
            val cola = ArrayDeque<File>()
            cola.addAll(raices)
            var truncado = false
            while (cola.isNotEmpty()) {
                val nuevos = withContext(Dispatchers.IO) { escanearLote(cola, q, f, ocultos, vistas, rutasRaiz) }
                if (nuevos.isNotEmpty()) {
                    lista.addAll(nuevos)
                    publicar(lista, q)
                }
                if (lista.size >= MAX_RESULTADOS) {
                    truncado = true
                    break
                }
            }
            spinner.visibility = View.GONE
            if (truncado) toast("Mostrando solo los primeros $MAX_RESULTADOS resultados")
            if (lista.isEmpty()) mostrar(emptyList(), "Sin resultados para «$q»", "🔍")
        }
    }

    private fun publicar(lista: List<ArchivoItem>, q: String) {
        val ordenados = lista.sortedWith(
            compareBy<ArchivoItem> { !it.nombre.startsWith(q, ignoreCase = true) }
                .thenByDescending { it.ultimaModificacion }
        )
        mostrar(ordenados, "", "🔍")
    }

    /** Recorre directorios durante unos 250 ms y devuelve las coincidencias nuevas. */
    private fun escanearLote(
        cola: ArrayDeque<File>, q: String, f: Filtro, ocultos: Boolean,
        vistas: HashSet<String>, rutasRaiz: Set<String>
    ): List<ArchivoItem> {
        val salida = ArrayList<ArchivoItem>()
        val limite = System.currentTimeMillis() + 250
        while (cola.isNotEmpty() && System.currentTimeMillis() < limite) {
            val dir = cola.removeFirst()
            val hijos = dir.listFiles() ?: continue
            val dirEsRaiz = dir.absolutePath in rutasRaiz
            for (h in hijos) {
                val nombre = h.name
                if (!ocultos && nombre.startsWith(".")) continue
                if (h.isDirectory) {
                    if (nombre == Papelera.NOMBRE) continue
                    if (dirEsRaiz && nombre == "Android") continue
                    cola.add(h)
                }
                if (nombre.contains(q, ignoreCase = true)) {
                    val item = ArchivoItem.desde(h)
                    if (f.admite(item) && vistas.add(item.clave)) salida.add(item)
                }
            }
        }
        return salida
    }
}
