package com.vigia.app

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.vigia.app.core.FilaLinea
import com.vigia.app.core.SessionManager
import com.vigia.app.ui.LineaAdapter
import com.vigia.app.util.Formato
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Línea de tiempo: DNS, eventos del sistema, cámara/micrófono y actividad en pantalla de la sesión,
 * mezclados por hora. Permite filtrar por categoría, tipo, app, texto y ventana de ±60 s alrededor
 * de un evento, para cruzar lo que pasó a la vez en distintos módulos.
 */
class LineaTiempoActivity : AppCompatActivity() {

    private lateinit var gestor: SessionManager
    private lateinit var adaptador: LineaAdapter
    private lateinit var etBuscar: EditText
    private lateinit var lineaResumen: TextView
    private lateinit var lineaVacio: TextView
    private var sesionId = -1L

    private var todas: List<FilaLinea> = emptyList()
    private val categorias = arrayOf("DNS", "SISTEMA", "SENSOR", "PANTALLA")
    private val activas = booleanArrayOf(true, true, true, true)
    private var filtroApp: String? = null
    private var filtroTipo: String? = null
    private var centro: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_linea_tiempo)

        sesionId = intent.getLongExtra(SessionDetailActivity.EXTRA_SESION, -1L)
        if (sesionId < 0) {
            finish()
            return
        }
        gestor = SessionManager.obtener(applicationContext)
        val centroInicial = intent.getLongExtra(EXTRA_CENTRO, -1L)
        if (centroInicial > 0L) centro = centroInicial

        etBuscar = findViewById(R.id.etBuscar)
        lineaResumen = findViewById(R.id.lineaResumen)
        lineaVacio = findViewById(R.id.lineaVacio)
        val lista = findViewById<ListView>(R.id.listaLinea)
        adaptador = LineaAdapter(this)
        lista.adapter = adaptador

        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnCategorias).setOnClickListener { elegirCategorias() }
        findViewById<MaterialButton>(R.id.btnPorTipo).setOnClickListener { elegirTipo() }
        findViewById<MaterialButton>(R.id.btnPorApp).setOnClickListener { elegirApp() }
        findViewById<MaterialButton>(R.id.btnLimpiar).setOnClickListener { quitarFiltros() }
        etBuscar.doAfterTextChanged { aplicar() }
        lista.setOnItemClickListener { _, _, posicion, _ ->
            val fila = adaptador.getItem(posicion)
            if (fila != null) mostrarOpciones(fila)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gestor.lineaTiempo(sesionId).collect { filas ->
                    todas = filas
                    aplicar()
                }
            }
        }
    }

    private fun aplicar() {
        val texto = etBuscar.text.toString().trim().lowercase()
        val fa = filtroApp
        val ft = filtroTipo
        val c = centro

        val filtradas = todas.filter { f ->
            val indice = categorias.indexOf(f.cat)
            (indice < 0 || activas[indice]) &&
                (fa == null || f.app == fa) &&
                (ft == null || f.tipo == ft) &&
                (c == null || abs(f.ts - c) <= 60000L) &&
                (texto.isEmpty() || f.app.lowercase().contains(texto) ||
                    f.tipo.lowercase().contains(texto) || f.texto.lowercase().contains(texto))
        }
        adaptador.actualizar(filtradas)

        val partes = ArrayList<String>()
        if (fa != null) partes.add("app " + fa)
        if (ft != null) partes.add("tipo " + ft)
        if (c != null) partes.add("±60 s de las " + Formato.hora(c))
        if (texto.isNotEmpty()) partes.add("texto «" + texto + "»")
        if (activas.any { !it }) {
            val vistas = ArrayList<String>()
            for (i in categorias.indices) {
                if (activas[i]) vistas.add(categorias[i])
            }
            partes.add("categorías " + vistas.joinToString("+"))
        }
        val cuenta = filtradas.size.toString() + " de " + todas.size + " eventos"
        lineaResumen.text = if (partes.isEmpty()) cuenta else "Filtro: " + partes.joinToString(" · ") + "\n" + cuenta

        lineaVacio.visibility = if (filtradas.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun quitarFiltros() {
        filtroApp = null
        filtroTipo = null
        centro = null
        for (i in activas.indices) activas[i] = true
        etBuscar.setText("")
        aplicar()
    }

    private fun mostrarOpciones(f: FilaLinea) {
        val etiquetas = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()

        etiquetas.add("Ver lo que pasó ±60 s alrededor de las " + Formato.hora(f.ts))
        acciones.add { centro = f.ts }
        if (f.app.isNotEmpty()) {
            etiquetas.add("Solo la app " + f.app)
            acciones.add { filtroApp = f.app }
        }
        etiquetas.add("Solo el tipo " + f.cat + " · " + f.tipo)
        acciones.add { filtroTipo = f.tipo }

        AlertDialog.Builder(this)
            .setTitle(f.cat + " · " + f.tipo)
            .setItems(etiquetas.toTypedArray()) { _, cual ->
                acciones[cual]()
                aplicar()
            }
            .show()
    }

    private fun elegirCategorias() {
        val marcadas = activas.copyOf()
        AlertDialog.Builder(this)
            .setTitle(R.string.elegir_categorias)
            .setMultiChoiceItems(categorias, marcadas) { _, indice, valor -> marcadas[indice] = valor }
            .setPositiveButton(R.string.aplicar) { _, _ ->
                for (i in activas.indices) activas[i] = marcadas[i]
                aplicar()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun elegirTipo() {
        val conteo = todas.groupingBy { it.cat + " · " + it.tipo }.eachCount().entries
            .sortedByDescending { it.value }.take(100)
        if (conteo.isEmpty()) return
        val etiquetas = conteo.map { it.key + " — " + it.value }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.elegir_tipo)
            .setItems(etiquetas) { _, cual ->
                filtroTipo = conteo[cual].key.substringAfter(" · ")
                aplicar()
            }
            .show()
    }

    private fun elegirApp() {
        val conteo = todas.filter { it.app.isNotEmpty() }.groupingBy { it.app }.eachCount().entries
            .sortedByDescending { it.value }.take(100)
        if (conteo.isEmpty()) return
        val etiquetas = conteo.map { it.key + " — " + it.value }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.elegir_app)
            .setItems(etiquetas) { _, cual ->
                filtroApp = conteo[cual].key
                aplicar()
            }
            .show()
    }

    companion object {
        const val EXTRA_CENTRO = "centro_ts"
    }
}
