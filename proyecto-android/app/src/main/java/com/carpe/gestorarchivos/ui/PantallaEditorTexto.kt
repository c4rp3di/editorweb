package com.carpe.gestorarchivos.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.gestorarchivos.R
import com.carpe.gestorarchivos.data.Ajustes
import com.carpe.gestorarchivos.data.GestorArchivos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PantallaEditorTexto : Fragment() {

    companion object {
        private const val ARG_RUTA = "ruta"
        fun nueva(ruta: String) = PantallaEditorTexto().apply {
            arguments = Bundle().apply { putString(ARG_RUTA, ruta) }
        }
    }

    private class Cambio(var inicio: Int, var antes: String, var despues: String, var tiempo: Long)

    private lateinit var editor: EditText
    private lateinit var nombre: TextView
    private lateinit var info: TextView
    private lateinit var numeros: TextView
    private lateinit var scrollEditor: ScrollView
    private lateinit var cargandoEditor: ProgressBar
    private lateinit var barraBuscar: View
    private lateinit var entradaBuscar: EditText
    private lateinit var entradaReemplazar: EditText
    private lateinit var contador: TextView
    private lateinit var botonCaso: TextView
    private lateinit var botonDeshacer: TextView
    private lateinit var botonRehacer: TextView
    private lateinit var botonNumeros: TextView
    private lateinit var ajustes: Ajustes

    private var archivo: File? = null
    private var lectura: GestorArchivos.TextoLeido? = null
    private val deshacerPila = ArrayList<Cambio>()
    private val rehacerPila = ArrayList<Cambio>()
    private var aplicando = false
    private var cargado = false
    private var posGuardada = 0
    private var noFusionar = false
    private var antesTexto = ""
    private var sensibleMayusculas = false
    private var lineasMostradas = -1

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        val v = inflater.inflate(R.layout.dialog_editor_texto, contenedor, false)
        ajustes = Ajustes(requireContext())
        archivo = arguments?.getString(ARG_RUTA)?.let { File(it) }

        editor = v.findViewById(R.id.editorTexto)
        nombre = v.findViewById(R.id.nombreEditor)
        info = v.findViewById(R.id.infoEditor)
        numeros = v.findViewById(R.id.numerosLinea)
        scrollEditor = v.findViewById(R.id.scrollEditor)
        cargandoEditor = v.findViewById(R.id.cargandoEditor)
        barraBuscar = v.findViewById(R.id.barraBuscar)
        entradaBuscar = v.findViewById(R.id.entradaBuscar)
        entradaReemplazar = v.findViewById(R.id.entradaReemplazar)
        contador = v.findViewById(R.id.contadorBuscar)
        botonCaso = v.findViewById(R.id.botonCaso)
        botonDeshacer = v.findViewById(R.id.botonDeshacer)
        botonRehacer = v.findViewById(R.id.botonRehacer)
        botonNumeros = v.findViewById(R.id.botonNumeros)

        nombre.text = archivo?.name ?: "archivo"
        editor.isEnabled = false

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!aplicando && cargado && s != null) antesTexto = s.subSequence(start, start + count).toString()
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (aplicando || !cargado || s == null) return
                registrarCambio(start, antesTexto, s.subSequence(start, start + count).toString())
            }

            override fun afterTextChanged(s: Editable?) {
                actualizarEstado()
                if (numeros.visibility == View.VISIBLE) actualizarNumeros()
            }
        })

        v.findViewById<View>(R.id.botonVolver).setOnClickListener { salir() }
        v.findViewById<View>(R.id.botonGuardarTexto).setOnClickListener { guardar(null) }
        botonDeshacer.setOnClickListener { deshacer() }
        botonRehacer.setOnClickListener { rehacer() }
        v.findViewById<View>(R.id.botonBuscarEditor).setOnClickListener { alternarBusqueda() }
        v.findViewById<View>(R.id.botonCerrarBuscar).setOnClickListener { barraBuscar.visibility = View.GONE }
        v.findViewById<View>(R.id.botonSiguiente).setOnClickListener { buscar(true) }
        v.findViewById<View>(R.id.botonAnterior).setOnClickListener { buscar(false) }
        v.findViewById<View>(R.id.botonReemplazar).setOnClickListener { reemplazarUno() }
        v.findViewById<View>(R.id.botonReemplazarTodo).setOnClickListener { reemplazarTodo() }
        botonCaso.alpha = 0.5f
        botonCaso.setOnClickListener {
            sensibleMayusculas = !sensibleMayusculas
            botonCaso.alpha = if (sensibleMayusculas) 1f else 0.5f
            actualizarContador()
        }
        entradaBuscar.doAfterTextChanged { actualizarContador() }
        botonNumeros.setOnClickListener {
            ajustes.numerosDeLinea = !ajustes.numerosDeLinea
            aplicarNumerosLinea(ajustes.numerosDeLinea)
        }
        aplicarNumerosLinea(ajustes.numerosDeLinea)
        actualizarBotonesDeshacer()
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { salir() }
        })
        val f = archivo
        if (f == null) { volverAtras(); return }
        cargandoEditor.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val l = withContext(Dispatchers.IO) { GestorArchivos.leerTextoDetectado(f) }
            cargandoEditor.visibility = View.GONE
            if (l == null) {
                toast("No se pudo leer: ¿es un archivo de texto?", true)
                volverAtras()
                return@launch
            }
            lectura = l
            aplicando = true
            editor.setText(l.texto)
            aplicando = false
            editor.isEnabled = true
            cargado = true
            info.text = l.etiqueta
            deshacerPila.clear()
            rehacerPila.clear()
            posGuardada = 0
            noFusionar = true
            lineasMostradas = -1
            actualizarEstado()
            actualizarNumeros()
        }
    }

    // ---------------------------------------------------------- estado / deshacer

    private fun hayCambios(): Boolean = cargado && deshacerPila.size != posGuardada

    private fun actualizarEstado() {
        val nombreArchivo = archivo?.name ?: "archivo"
        nombre.text = if (hayCambios()) "● $nombreArchivo" else nombreArchivo
        actualizarBotonesDeshacer()
    }

    private fun actualizarBotonesDeshacer() {
        botonDeshacer.alpha = if (deshacerPila.isEmpty()) 0.35f else 1f
        botonRehacer.alpha = if (rehacerPila.isEmpty()) 0.35f else 1f
    }

    private fun registrarCambio(inicio: Int, antes: String, despues: String) {
        if (antes == despues) return
        rehacerPila.clear()
        if (posGuardada > deshacerPila.size) posGuardada = -1
        val ahora = System.currentTimeMillis()
        val ultimo = deshacerPila.lastOrNull()
        if (!noFusionar && ultimo != null && ahora - ultimo.tiempo < 1200) {
            if (antes.isEmpty() && despues.length == 1 && ultimo.antes.isEmpty() &&
                inicio == ultimo.inicio + ultimo.despues.length && despues != "\n"
            ) {
                ultimo.despues += despues
                ultimo.tiempo = ahora
                return
            }
            if (despues.isEmpty() && antes.length == 1 && ultimo.despues.isEmpty() &&
                inicio + antes.length == ultimo.inicio
            ) {
                ultimo.inicio = inicio
                ultimo.antes = antes + ultimo.antes
                ultimo.tiempo = ahora
                return
            }
        }
        noFusionar = false
        deshacerPila.add(Cambio(inicio, antes, despues, ahora))
        if (deshacerPila.size > 500) {
            deshacerPila.removeAt(0)
            posGuardada = if (posGuardada > 0) posGuardada - 1 else -1
        }
    }

    private fun deshacer() {
        if (deshacerPila.isEmpty()) return
        val c = deshacerPila.removeAt(deshacerPila.size - 1)
        aplicando = true
        editor.text.replace(c.inicio, c.inicio + c.despues.length, c.antes)
        aplicando = false
        rehacerPila.add(c)
        noFusionar = true
        editor.setSelection((c.inicio + c.antes.length).coerceIn(0, editor.text.length))
        actualizarEstado()
    }

    private fun rehacer() {
        if (rehacerPila.isEmpty()) return
        val c = rehacerPila.removeAt(rehacerPila.size - 1)
        aplicando = true
        editor.text.replace(c.inicio, c.inicio + c.antes.length, c.despues)
        aplicando = false
        deshacerPila.add(c)
        noFusionar = true
        editor.setSelection((c.inicio + c.despues.length).coerceIn(0, editor.text.length))
        actualizarEstado()
    }

    // ---------------------------------------------------------- números de línea

    private fun aplicarNumerosLinea(activo: Boolean) {
        numeros.visibility = if (activo) View.VISIBLE else View.GONE
        editor.setHorizontallyScrolling(activo)
        botonNumeros.alpha = if (activo) 1f else 0.5f
        if (activo) {
            lineasMostradas = -1
            actualizarNumeros()
        }
    }

    private fun actualizarNumeros() {
        if (numeros.visibility != View.VISIBLE) return
        val t = editor.text
        var n = 1
        for (i in 0 until t.length) if (t[i] == '\n') n++
        if (n == lineasMostradas) return
        lineasMostradas = n
        val sb = StringBuilder(n * 4)
        for (i in 1..n) {
            if (i > 1) sb.append('\n')
            sb.append(i)
        }
        numeros.text = sb.toString()
    }

    // ---------------------------------------------------------- buscar / reemplazar

    private fun alternarBusqueda() {
        if (barraBuscar.visibility == View.VISIBLE) {
            barraBuscar.visibility = View.GONE
        } else {
            barraBuscar.visibility = View.VISIBLE
            entradaBuscar.requestFocus()
            actualizarContador()
        }
    }

    private fun actualizarContador() {
        val q = entradaBuscar.text.toString()
        if (q.isEmpty()) { contador.text = ""; return }
        val t = editor.text.toString()
        val ic = !sensibleMayusculas
        var n = 0
        var i = t.indexOf(q, 0, ic)
        while (i >= 0 && n < 9999) {
            n++
            i = t.indexOf(q, i + q.length, ic)
        }
        contador.text = if (n >= 9999) "9999+" else n.toString()
    }

    private fun buscar(adelante: Boolean): Boolean {
        val q = entradaBuscar.text.toString()
        if (q.isEmpty()) return false
        val t = editor.text.toString()
        val ic = !sensibleMayusculas
        var i = if (adelante) {
            t.indexOf(q, editor.selectionEnd.coerceAtLeast(0), ic)
        } else {
            val ini = editor.selectionStart - 1
            if (ini < 0) -1 else t.lastIndexOf(q, ini, ic)
        }
        if (i < 0) i = if (adelante) t.indexOf(q, 0, ic) else t.lastIndexOf(q, t.length, ic)
        if (i < 0) {
            toast("Sin resultados")
            return false
        }
        editor.requestFocus()
        editor.setSelection(i, i + q.length)
        scrollEditor.post {
            val layout = editor.layout ?: return@post
            val linea = layout.getLineForOffset(i)
            val y = layout.getLineTop(linea) + editor.paddingTop
            scrollEditor.smoothScrollTo(0, (y - scrollEditor.height / 3).coerceAtLeast(0))
        }
        return true
    }

    private fun reemplazarUno() {
        val q = entradaBuscar.text.toString()
        if (q.isEmpty()) return
        val ini = editor.selectionStart
        val fin = editor.selectionEnd
        val seleccion = if (ini in 0..fin) editor.text.substring(ini, fin) else ""
        if (seleccion.isNotEmpty() && seleccion.equals(q, ignoreCase = !sensibleMayusculas)) {
            editor.text.replace(ini, fin, entradaReemplazar.text.toString())
            actualizarContador()
        }
        buscar(true)
    }

    private fun reemplazarTodo() {
        val q = entradaBuscar.text.toString()
        if (q.isEmpty()) return
        val reemplazo = entradaReemplazar.text.toString()
        val t = editor.text.toString()
        val ic = !sensibleMayusculas
        var i = t.indexOf(q, 0, ic)
        if (i < 0) { toast("Sin resultados"); return }
        val sb = StringBuilder(t.length)
        var ultimo = 0
        var n = 0
        while (i >= 0) {
            sb.append(t, ultimo, i).append(reemplazo)
            ultimo = i + q.length
            n++
            i = t.indexOf(q, ultimo, ic)
        }
        sb.append(t, ultimo, t.length)
        editor.text.replace(0, editor.text.length, sb.toString())
        toast("$n reemplazo(s)")
        actualizarContador()
    }

    // ---------------------------------------------------------- guardar / salir

    private fun guardar(despues: (() -> Unit)?) {
        val f = archivo ?: return
        if (!cargado) return
        val texto = editor.text.toString()
        val marca = deshacerPila.size
        val ref = lectura
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { GestorArchivos.escribirTexto(f, texto, ref) }
            if (ok) {
                posGuardada = marca
                noFusionar = true
                actualizarEstado()
                toast("Guardado")
                despues?.invoke()
            } else {
                toast("No se pudo guardar el archivo", true)
            }
        }
    }

    private fun salir() {
        if (!hayCambios()) {
            volverAtras()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Cambios sin guardar")
            .setMessage("¿Quieres guardar los cambios antes de salir?")
            .setPositiveButton("Guardar") { _, _ -> guardar { volverAtras() } }
            .setNegativeButton("Descartar") { _, _ -> volverAtras() }
            .setNeutralButton("Seguir editando", null)
            .show()
    }
}
