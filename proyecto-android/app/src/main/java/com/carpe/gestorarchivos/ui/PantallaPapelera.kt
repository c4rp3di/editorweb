package com.carpe.gestorarchivos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PantallaPapelera : Fragment() {

    private lateinit var papelera: Papelera
    private lateinit var adaptador: AdaptadorFilas
    private lateinit var resumen: TextView
    private lateinit var vacio: View
    private lateinit var textoVacio: TextView
    private lateinit var iconoVacio: TextView
    private lateinit var cargando: View
    private lateinit var textoCargando: TextView
    private lateinit var lista: RecyclerView

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_lista_simple, contenedor, false)
        papelera = Papelera(requireContext())
        v.findViewById<TextView>(R.id.tituloLista).text = "Papelera"
        resumen = v.findViewById(R.id.resumenLista)
        vacio = v.findViewById(R.id.estadoListaVacio)
        textoVacio = v.findViewById(R.id.textoListaVacio)
        iconoVacio = v.findViewById(R.id.iconoListaVacio)
        cargando = v.findViewById(R.id.cargandoLista)
        textoCargando = v.findViewById(R.id.textoCargandoLista)
        lista = v.findViewById(R.id.listaFilas)
        iconoVacio.text = "🗑️"
        textoVacio.text = "La papelera está vacía"
        resumen.text = "Los elementos se eliminan automáticamente a los 30 días."
        resumen.visibility = View.VISIBLE

        adaptador = AdaptadorFilas(alTocar = { fila -> (fila.dato as? Papelera.Entrada)?.let { opciones(it) } })
        lista.layoutManager = LinearLayoutManager(requireContext())
        lista.adapter = adaptador

        v.findViewById<View>(R.id.botonVolverLista).setOnClickListener { volverAtras() }
        val vaciar = v.findViewById<ImageView>(R.id.botonAccionLista)
        vaciar.visibility = View.VISIBLE
        vaciar.contentDescription = "Vaciar papelera"
        vaciar.setOnClickListener { confirmarVaciar() }
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { volverAtras() }
        })
        cargar()
    }

    private fun cargar() {
        cargando.visibility = View.VISIBLE
        textoCargando.text = "Leyendo papelera…"
        viewLifecycleOwner.lifecycleScope.launch {
            val filas = withContext(Dispatchers.IO) {
                papelera.listar().map { e ->
                    val tam = GestorArchivos.tamanoTotal(File(e.rutaPapelera))
                    val icono = if (e.esCarpeta) "📁" else Iconos.de(ArchivoItem.categoriaDe(e.nombre.substringAfterLast('.', "").lowercase()))
                    Fila(
                        id = e.id,
                        icono = icono,
                        titulo = e.nombre,
                        subtitulo = "${Formato.tamano(tam)} · borrado ${Formato.fecha(e.fecha)}\n${e.rutaOriginal}",
                        dato = e
                    )
                }
            }
            cargando.visibility = View.GONE
            adaptador.establecer(filas)
            vacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun opciones(e: Papelera.Entrada) {
        AlertDialog.Builder(requireContext())
            .setTitle(e.nombre)
            .setItems(arrayOf("♻️ Restaurar", "🗑️ Eliminar definitivamente")) { _, i ->
                if (i == 0) restaurar(e) else confirmarEliminar(e)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun restaurar(e: Papelera.Entrada) {
        ejecutarConProgreso("Restaurando…", { papelera.restaurar(e) }) { r ->
            if (r.getOrDefault(false)) toast("Restaurado en ${File(e.rutaOriginal).parent}", true)
            else toast("No se pudo restaurar", true)
            cargar()
        }
    }

    private fun confirmarEliminar(e: Papelera.Entrada) {
        AlertDialog.Builder(requireContext())
            .setTitle("¿Eliminar definitivamente?")
            .setMessage("«${e.nombre}» se borrará para siempre.")
            .setPositiveButton("Eliminar") { _, _ ->
                ejecutarConProgreso("Eliminando…", { papelera.eliminarDefinitivo(e) }) { r ->
                    if (!r.getOrDefault(false)) toast("No se pudo eliminar", true)
                    cargar()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarVaciar() {
        if (adaptador.filas.isEmpty()) { toast("La papelera ya está vacía"); return }
        AlertDialog.Builder(requireContext())
            .setTitle("¿Vaciar la papelera?")
            .setMessage("Se borrarán definitivamente ${adaptador.filas.size} elemento(s).")
            .setPositiveButton("Vaciar") { _, _ ->
                ejecutarConProgreso("Vaciando…", { papelera.vaciar() }) { r ->
                    toast("${r.getOrDefault(0)} elemento(s) eliminados")
                    cargar()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
