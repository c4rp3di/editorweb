package com.carpe.gestorarchivos.ui

import android.os.Bundle
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.AdaptadorFilas
import com.carpe.gestorarchivos.data.ArchivoItem
import com.carpe.gestorarchivos.data.Fila
import com.carpe.gestorarchivos.data.Formato
import com.carpe.gestorarchivos.data.GestorArchivos
import com.carpe.gestorarchivos.data.Iconos
import com.carpe.gestorarchivos.data.Papelera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Dos herramientas en una pantalla: analizador de espacio por carpeta y buscador de archivos duplicados. */
class PantallaAnalizador : Fragment() {

    companion object {
        const val MODO_ESPACIO = "espacio"
        const val MODO_DUPLICADOS = "duplicados"
        private const val ARG_MODO = "modo"
        private const val ARG_RUTA = "ruta"

        fun nueva(modo: String, ruta: String) = PantallaAnalizador().apply {
            arguments = Bundle().apply {
                putString(ARG_MODO, modo)
                putString(ARG_RUTA, ruta)
            }
        }
    }

    private class Grupo(val tamano: Long)

    private lateinit var papelera: Papelera
    private lateinit var adaptador: AdaptadorFilas
    private lateinit var titulo: TextView
    private lateinit var resumen: TextView
    private lateinit var vacio: View
    private lateinit var textoVacio: TextView
    private lateinit var iconoVacio: TextView
    private lateinit var cargando: View
    private lateinit var textoCargando: TextView
    private lateinit var boton: Button
    private lateinit var callbackAtras: OnBackPressedCallback

    private var modo = MODO_ESPACIO
    private val pila = ArrayList<File>()
    private var trabajo: Job? = null
    private val grupos = ArrayList<Grupo>()

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_lista_simple, contenedor, false)
        papelera = Papelera(requireContext())
        modo = arguments?.getString(ARG_MODO) ?: MODO_ESPACIO
        val raiz = File(arguments?.getString(ARG_RUTA) ?: "/")
        if (pila.isEmpty()) pila.add(raiz)

        titulo = v.findViewById(R.id.tituloLista)
        resumen = v.findViewById(R.id.resumenLista)
        vacio = v.findViewById(R.id.estadoListaVacio)
        textoVacio = v.findViewById(R.id.textoListaVacio)
        iconoVacio = v.findViewById(R.id.iconoListaVacio)
        cargando = v.findViewById(R.id.cargandoLista)
        textoCargando = v.findViewById(R.id.textoCargandoLista)
        boton = v.findViewById(R.id.botonPrincipalLista)

        adaptador = AdaptadorFilas(alTocar = { alTocar(it) }, alMarcar = { actualizarBoton() })
        val lista = v.findViewById<RecyclerView>(R.id.listaFilas)
        lista.layoutManager = LinearLayoutManager(requireContext())
        lista.adapter = adaptador

        v.findViewById<View>(R.id.botonVolverLista).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        callbackAtras = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (modo == MODO_ESPACIO && pila.size > 1) {
                    pila.removeAt(pila.size - 1)
                    cargarEspacio(pila[pila.size - 1])
                } else {
                    volverAtras()
                }
            }
        }
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callbackAtras)
        if (modo == MODO_ESPACIO) {
            titulo.text = "Analizar espacio"
            cargarEspacio(pila[pila.size - 1])
        } else {
            titulo.text = "Archivos duplicados"
            buscarDuplicados(pila[0])
        }
    }

    private fun mostrarCargando(texto: String) {
        cargando.visibility = View.VISIBLE
        textoCargando.text = texto
        vacio.visibility = View.GONE
        boton.visibility = View.GONE
    }

    private fun alTocar(fila: Fila) {
        if (modo == MODO_ESPACIO) {
            val f = fila.dato as? File ?: return
            if (f.isDirectory) {
                pila.add(f)
                cargarEspacio(f)
            }
        }
    }

    // -------------------------------------------------------------- espacio

    private fun cargarEspacio(carpeta: File) {
        trabajo?.cancel()
        mostrarCargando("Calculando tamaños…")
        titulo.text = carpeta.name.ifEmpty { "Analizar espacio" }
        trabajo = viewLifecycleOwner.lifecycleScope.launch {
            val datos = withContext(Dispatchers.IO) {
                val lista = ArrayList<Triple<File, Long, Boolean>>()
                val hijos = carpeta.listFiles()?.toList() ?: emptyList()
                for (h in hijos) {
                    if (!isActive) break
                    lista.add(Triple(h, GestorArchivos.tamanoTotal(h), h.isDirectory))
                }
                lista.sortByDescending { it.second }
                lista
            }
            cargando.visibility = View.GONE
            val total = datos.sumOf { it.second }
            val filas = datos.map { (f, tam, esCarpeta) ->
                val pct = if (total > 0) (tam * 100 / total).toInt() else 0
                Fila(
                    id = f.absolutePath,
                    icono = if (esCarpeta) "📁" else Iconos.de(ArchivoItem.categoriaDe(f.extension.lowercase())),
                    titulo = f.name,
                    subtitulo = "${Formato.tamano(tam)} · $pct%",
                    progreso = pct,
                    dato = f
                )
            }
            adaptador.establecer(filas)
            resumen.visibility = View.VISIBLE
            resumen.text = "Total en esta carpeta: ${Formato.tamano(total)}" + libre(carpeta)
            iconoVacio.text = "📭"
            textoVacio.text = "Carpeta vacía"
            vacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun libre(carpeta: File): String = try {
        val s = StatFs(carpeta.absolutePath)
        "\nLibre: ${Formato.tamano(s.availableBytes)} de ${Formato.tamano(s.totalBytes)}"
    } catch (e: Exception) {
        ""
    }

    // ----------------------------------------------------------- duplicados

    private fun CoroutineScope.hashDe(f: File, limite: Long): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val buffer = ByteArray(64 * 1024)
            var leidos = 0L
            FileInputStream(f).use { entrada ->
                while (leidos < limite && isActive) {
                    val n = entrada.read(buffer)
                    if (n <= 0) break
                    val usar = if (leidos + n > limite) (limite - leidos).toInt() else n
                    md.update(buffer, 0, usar)
                    leidos += usar
                }
            }
            if (!isActive) null else md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun buscarDuplicados(carpeta: File) {
        trabajo?.cancel()
        mostrarCargando("Explorando archivos…")
        resumen.visibility = View.GONE
        trabajo = viewLifecycleOwner.lifecycleScope.launch {
            val encontrados = withContext(Dispatchers.IO) {
                val porTamano = HashMap<Long, MutableList<File>>()
                var visitados = 0
                val cola = ArrayDeque<File>()
                cola.add(carpeta)
                while (cola.isNotEmpty() && isActive) {
                    val d = cola.removeFirst()
                    val hijos = d.listFiles() ?: continue
                    for (h in hijos) {
                        if (h.isDirectory) {
                            if (h.name != Papelera.NOMBRE) cola.add(h)
                        } else {
                            val len = h.length()
                            if (len > 0) porTamano.getOrPut(len) { ArrayList() }.add(h)
                            visitados++
                        }
                    }
                    textoCargando.post { textoCargando.text = "Explorando… $visitados archivos" }
                }
                val candidatos = porTamano.filter { it.value.size > 1 }
                val resultado = ArrayList<Pair<Long, List<File>>>()
                var hecho = 0
                for ((tam, archivos) in candidatos) {
                    if (!isActive) break
                    hecho++
                    textoCargando.post { textoCargando.text = "Comparando… $hecho/${candidatos.size}" }
                    val porParcial = HashMap<String, MutableList<File>>()
                    for (f in archivos) {
                        val h = hashDe(f, 64L * 1024) ?: continue
                        porParcial.getOrPut(h) { ArrayList() }.add(f)
                    }
                    for (grupoParcial in porParcial.values) {
                        if (grupoParcial.size < 2) continue
                        val porCompleto = HashMap<String, MutableList<File>>()
                        for (f in grupoParcial) {
                            val h = (if (tam <= 64L * 1024) hashDe(f, 64L * 1024) else hashDe(f, Long.MAX_VALUE)) ?: continue
                            porCompleto.getOrPut(h) { ArrayList() }.add(f)
                        }
                        for (g in porCompleto.values) if (g.size > 1) resultado.add(Pair(tam, g.sortedBy { it.lastModified() }))
                    }
                }
                resultado.sortByDescending { it.first * (it.second.size - 1) }
                resultado.take(500)
            }
            cargando.visibility = View.GONE
            grupos.clear()
            val filas = ArrayList<Fila>()
            encontrados.forEachIndexed { i, (tam, archivos) ->
                grupos.add(Grupo(tam))
                filas.add(
                    Fila(
                        id = "g$i", icono = "📑",
                        titulo = "${archivos.size} copias · ${Formato.tamano(tam)} c/u",
                        subtitulo = "Se pueden liberar ${Formato.tamano(tam * (archivos.size - 1))}",
                        cabecera = true, dato = null
                    )
                )
                archivos.forEachIndexed { j, f ->
                    filas.add(
                        Fila(
                            id = f.absolutePath,
                            icono = Iconos.de(ArchivoItem.categoriaDe(f.extension.lowercase())),
                            titulo = f.name,
                            subtitulo = (f.parent ?: "") + " · " + Formato.fechaCorta(f.lastModified()),
                            marcable = true, marcada = j > 0, dato = i
                        )
                    )
                }
            }
            adaptador.establecer(filas)
            iconoVacio.text = "✨"
            textoVacio.text = "No se encontraron archivos duplicados"
            vacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
            if (filas.isNotEmpty()) {
                resumen.visibility = View.VISIBLE
                resumen.text = "Toca un archivo para marcarlo o desmarcarlo. Por defecto se conserva el más antiguo de cada grupo."
            }
            actualizarBoton()
        }
    }

    private fun marcadas(): List<Fila> = adaptador.filas.filter { it.marcable && it.marcada }

    private fun actualizarBoton() {
        if (modo != MODO_DUPLICADOS) return
        val sel = marcadas()
        if (adaptador.filas.isEmpty()) { boton.visibility = View.GONE; return }
        boton.visibility = View.VISIBLE
        val bytes = sel.sumOf { grupos.getOrNull((it.dato as? Int) ?: -1)?.tamano ?: 0L }
        boton.text = "Mover a la papelera (${sel.size} · ${Formato.tamano(bytes)})"
        boton.isEnabled = sel.isNotEmpty()
        boton.setOnClickListener { confirmarBorrado() }
    }

    private fun confirmarBorrado() {
        val sel = marcadas()
        if (sel.isEmpty()) return
        val porGrupo = HashMap<Int, Int>()
        val totalGrupo = HashMap<Int, Int>()
        for (f in adaptador.filas) {
            if (!f.marcable) continue
            val g = f.dato as? Int ?: continue
            totalGrupo[g] = (totalGrupo[g] ?: 0) + 1
            if (f.marcada) porGrupo[g] = (porGrupo[g] ?: 0) + 1
        }
        val todasMarcadas = porGrupo.count { (g, n) -> n == totalGrupo[g] }
        val aviso = if (todasMarcadas > 0) "\n\n⚠️ En $todasMarcadas grupo(s) has marcado TODAS las copias." else ""
        AlertDialog.Builder(requireContext())
            .setTitle("¿Mover ${sel.size} archivo(s) a la papelera?")
            .setMessage("Podrás restaurarlos desde la papelera durante 30 días.$aviso")
            .setPositiveButton("Mover") { _, _ -> borrar(sel) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun borrar(sel: List<Fila>) {
        val ids = sel.map { it.id }.toSet()
        ejecutarConProgreso("Moviendo a la papelera…", { dlg ->
            var ok = 0
            for (id in ids) {
                dlg.actualizar(File(id).name)
                if (papelera.enviar(File(id))) ok++
            }
            ok
        }) { r ->
            toast("${r.getOrDefault(0)} archivo(s) movidos a la papelera")
            val restantes = limpiarGrupos(adaptador.filas.filterNot { it.id in ids })
            adaptador.establecer(restantes)
            vacio.visibility = if (restantes.isEmpty()) View.VISIBLE else View.GONE
            actualizarBoton()
        }
    }

    /** Quita las cabeceras de grupos que ya no tienen al menos dos copias. */
    private fun limpiarGrupos(filas: List<Fila>): List<Fila> {
        val salida = ArrayList<Fila>()
        var i = 0
        while (i < filas.size) {
            val cab = filas[i]
            if (!cab.cabecera) { i++; continue }
            var j = i + 1
            val hijos = ArrayList<Fila>()
            while (j < filas.size && !filas[j].cabecera) { hijos.add(filas[j]); j++ }
            if (hijos.size >= 2) {
                salida.add(cab)
                salida.addAll(hijos)
            }
            i = j
        }
        return salida
    }
}
