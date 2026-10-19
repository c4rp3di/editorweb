package com.carpe.descompresor.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carpe.descompresor.crear.CreadorTar
import com.carpe.descompresor.crear.CreadorZip
import com.carpe.descompresor.util.Formato
import com.carpe.descompresor.util.Iconos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PantallaLista(
    private val activity: AppCompatActivity,
    private val vm: ContenedorViewModel,
    private val lanz: Lanzadores
) {
    val vista: View

    private val info: TextView
    private val estado: TextView
    private val botonCerrar: com.google.android.material.button.MaterialButton
    private val botonExtraer: com.google.android.material.button.MaterialButton
    private val adapter: ArbolAdapter
    private val campoBusqueda: EditText
    private val spinner: Spinner
    private var ignorarSpinner = true
    private var dialogoCarga: AlertDialog? = null
    private var barraCarga: ProgressBar? = null
    private var textoCarga: TextView? = null

    init {
        val raiz = LinearLayout(activity)
        raiz.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(activity, 8)
        raiz.setPadding(p, p, p, 0)

        val fila1 = Ui.fila(activity)
        val bAbrir = Ui.boton(activity, "Abrir archivo") { abrirArchivo() }
        val bCrear = Ui.botonSecundario(activity, "Crear…") { dialogoCrear() }
        bAbrir.layoutParams = Ui.pesoUno()
        bCrear.layoutParams = Ui.pesoUno()
        fila1.addView(bAbrir)
        fila1.addView(bCrear)
        raiz.addView(fila1)

        info = Ui.texto(activity, "", 13f)
        info.setPadding(Ui.dp(activity, 4), Ui.dp(activity, 6), 0, 0)
        raiz.addView(info)

        botonCerrar = Ui.botonSecundario(activity, "Cerrar contenedor") { vm.cerrar() }
        raiz.addView(botonCerrar)

        estado = Ui.texto(activity, "", 13f)
        estado.setPadding(Ui.dp(activity, 4), Ui.dp(activity, 4), 0, 0)
        raiz.addView(estado)

        val filaBusq = Ui.fila(activity)
        campoBusqueda = EditText(activity)
        campoBusqueda.hint = "Buscar por nombre…"
        campoBusqueda.setSingleLine(true)
        campoBusqueda.layoutParams = Ui.pesoUno()
        campoBusqueda.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString() ?: ""
                if (t != vm.consulta) {
                    vm.consulta = t
                    vm.notificar()
                }
            }
        })
        spinner = Spinner(activity)
        val opciones = ArrayList<String>()
        opciones.add("Todo")
        for (c in Iconos.Categoria.values()) opciones.add(c.etiqueta)
        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, opciones)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (ignorarSpinner) {
                    ignorarSpinner = false
                    return
                }
                vm.filtro = if (pos == 0) null else Iconos.Categoria.values()[pos - 1]
                vm.notificar()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        filaBusq.addView(campoBusqueda)
        filaBusq.addView(spinner)
        raiz.addView(filaBusq)

        val filaSel = Ui.fila(activity)
        val bTodo = Ui.botonSecundario(activity, "Marcar todo") { vm.marcarTodo() }
        val bNada = Ui.botonSecundario(activity, "Desmarcar") { vm.desmarcarTodo() }
        bTodo.layoutParams = Ui.pesoUno()
        bNada.layoutParams = Ui.pesoUno()
        filaSel.addView(bTodo)
        filaSel.addView(bNada)
        raiz.addView(filaSel)

        val lista = RecyclerView(activity)
        lista.layoutManager = LinearLayoutManager(activity)
        adapter = ArbolAdapter(activity, vm) { n -> PantallaDetalles.mostrar(activity, vm, lanz, n) }
        lista.adapter = adapter
        lista.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        raiz.addView(lista)

        botonExtraer = Ui.boton(activity, "Extraer todo") { extraer() }
        raiz.addView(botonExtraer, Ui.anchoTotal(activity, 4))

        vista = raiz

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.revision.collect { refrescar() }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.progreso.collect { actualizarCarga(it) }
            }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dialogoCarga?.dismiss()
                dialogoCarga = null
            }
        })
        refrescar()
    }

    private fun mostrarDialogoCarga() {
        val cont = Ui.vertical(activity, 20)
        val texto = Ui.texto(activity, "Preparando…", 14f)
        val barra = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        barra.isIndeterminate = true
        cont.addView(texto)
        cont.addView(barra, Ui.anchoTotal(activity, 12))
        val d = AlertDialog.Builder(activity)
            .setTitle("Abriendo archivo")
            .setView(cont)
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            vm.cancelarApertura()
            texto.text = "Cancelando…"
        }
        dialogoCarga = d
        textoCarga = texto
        barraCarga = barra
        actualizarCarga(vm.progreso.value)
    }

    private fun actualizarCarga(p: ProgresoApertura) {
        val t = textoCarga ?: return
        val b = barraCarga ?: return
        if (!p.activo) return
        if (p.total > 0L && p.hecho >= p.total && p.texto.startsWith("Copiando")) {
            t.text = "Analizando contenido…"
            b.isIndeterminate = true
        } else if (p.total > 0L) {
            b.isIndeterminate = false
            b.max = 1000
            b.progress = ((p.hecho * 1000L) / p.total).toInt().coerceIn(0, 1000)
            t.text = p.texto + " " + Formato.bytes(p.hecho) + " / " + Formato.bytes(p.total)
        } else {
            b.isIndeterminate = true
            t.text = p.texto + (if (p.hecho > 0L) " " + Formato.bytes(p.hecho) else "")
        }
    }

    private fun gestionarCarga() {
        if (vm.cargando) {
            if (dialogoCarga == null) mostrarDialogoCarga()
        } else {
            dialogoCarga?.dismiss()
            dialogoCarga = null
            textoCarga = null
            barraCarga = null
        }
    }

    private fun abrirArchivo() {
        lanz.abrirDocumento { uri ->
            if (uri != null) {
                campoBusqueda.setText("")
                vm.abrir(activity, uri, false)
            }
        }
    }

    private fun refrescar() {
        val c = vm.contenedor
        info.text = if (c == null) "Abre un archivo comprimido (ZIP, 7z, RAR, TAR, GZ, APK…)"
        else vm.rutaNavegacion() + "\n${c.formato} · ${Formato.bytes(c.tamanoArchivo)} · ${vm.totalArchivos()} archivos"
        botonCerrar.visibility = if (c == null) View.GONE else View.VISIBLE
        botonCerrar.text = if (vm.nivel > 0) "⬅ Volver al contenedor anterior" else "Cerrar contenedor"
        estado.text = when {
            vm.error != null -> "⚠ " + vm.error
            else -> ""
        }
        estado.visibility = if (estado.text.isEmpty()) View.GONE else View.VISIBLE

        if (campoBusqueda.text.toString() != vm.consulta) campoBusqueda.setText(vm.consulta)
        val posFiltro = vm.filtro?.let { it.ordinal + 1 } ?: 0
        if (spinner.selectedItemPosition != posFiltro) {
            ignorarSpinner = true
            spinner.setSelection(posFiltro)
        }

        adapter.resaltar = vm.consulta
        adapter.modoPlano = vm.consulta.isNotBlank() || vm.filtro != null
        adapter.actualizar(vm.visibles())
        botonExtraer.text = if (vm.seleccion.isEmpty()) "Extraer todo" else "Extraer selección (${vm.seleccion.size})"
        botonExtraer.isEnabled = c != null
        gestionarCarga()
    }

    private fun extraer() {
        val c = vm.contenedor ?: return
        val rutas: Set<String> = if (vm.seleccion.isEmpty()) {
            c.entradas.filter { !it.esDirectorio }.map { it.ruta }.toSet()
        } else {
            HashSet(vm.seleccion)
        }
        if (rutas.isEmpty()) {
            Ui.toast(activity, "No hay archivos para extraer")
            return
        }
        DialogoExtraccion.mostrar(activity, vm, lanz, rutas)
    }

    private fun dialogoCrear() {
        val formatos = arrayOf("ZIP", "TAR", "TAR.GZ")
        AlertDialog.Builder(activity)
            .setTitle("Crear archivo comprimido")
            .setItems(formatos) { _, which ->
                lanz.elegirVarios { uris ->
                    if (uris.isEmpty()) return@elegirVarios
                    val ext = when (which) { 0 -> "zip"; 1 -> "tar"; else -> "tar.gz" }
                    lanz.crearDocumento("nuevo.$ext") { destino ->
                        if (destino == null) return@crearDocumento
                        activity.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val salida = activity.contentResolver.openOutputStream(destino, "wt")
                                        ?: throw java.io.IOException("No se pudo escribir en el destino")
                                    salida.use {
                                        when (which) {
                                            0 -> CreadorZip.crear(activity, uris, it, 6)
                                            1 -> CreadorTar.crear(activity, uris, it, false)
                                            else -> CreadorTar.crear(activity, uris, it, true)
                                        }
                                    }
                                }
                                Ui.toast(activity, "✔ Archivo creado (${uris.size} archivos)")
                            } catch (t: Throwable) {
                                Ui.toast(activity, "✘ No se pudo crear: " + Ui.mensajeError(t))
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
