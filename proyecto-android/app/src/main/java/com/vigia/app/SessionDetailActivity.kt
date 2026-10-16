package com.vigia.app

import android.content.Intent
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
import com.google.android.material.checkbox.MaterialCheckBox
import com.vigia.app.core.SessionManager
import com.vigia.app.store.DnsEventEntity
import com.vigia.app.ui.EventosAdapter
import com.vigia.app.util.Formato
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var gestor: SessionManager
    private lateinit var adaptador: EventosAdapter
    private lateinit var detTitulo: TextView
    private lateinit var detResumen: TextView
    private lateinit var detVacio: TextView
    private lateinit var detFiltro: TextView
    private lateinit var etBuscar: EditText
    private lateinit var chkFallidas: MaterialCheckBox
    private lateinit var btnBorrar: MaterialButton
    private var sesionId = -1L

    private var todos: List<DnsEventEntity> = emptyList()
    private var filtroDominio: String? = null
    private var filtroApp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_detail)

        sesionId = intent.getLongExtra(EXTRA_SESION, -1L)
        if (sesionId < 0) {
            finish()
            return
        }
        gestor = SessionManager.obtener(applicationContext)

        detTitulo = findViewById(R.id.detTitulo)
        detResumen = findViewById(R.id.detResumen)
        detVacio = findViewById(R.id.detVacio)
        detFiltro = findViewById(R.id.detFiltro)
        etBuscar = findViewById(R.id.etBuscar)
        chkFallidas = findViewById(R.id.chkFallidas)
        btnBorrar = findViewById(R.id.btnBorrar)
        val btnVolver = findViewById<MaterialButton>(R.id.btnVolver)
        val btnApps = findViewById<MaterialButton>(R.id.btnApps)
        val btnDominios = findViewById<MaterialButton>(R.id.btnDominios)
        val btnLimpiar = findViewById<MaterialButton>(R.id.btnLimpiar)
        val lista = findViewById<ListView>(R.id.listaEventos)

        adaptador = EventosAdapter(this)
        lista.adapter = adaptador

        btnVolver.setOnClickListener { finish() }
        btnBorrar.setOnClickListener { confirmarBorrado() }
        findViewById<MaterialButton>(R.id.btnLinea).setOnClickListener { abrir(LineaTiempoActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnInventario).setOnClickListener { abrir(InventarioActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnInforme).setOnClickListener { abrir(InformeActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnHallazgos).setOnClickListener { abrir(HallazgosActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnAnalizarSesion).setOnClickListener { analizarAhora(it as MaterialButton) }
        btnApps.setOnClickListener { elegirApp() }
        btnDominios.setOnClickListener { elegirDominio() }
        btnLimpiar.setOnClickListener { quitarFiltros() }
        etBuscar.doAfterTextChanged { aplicarFiltros() }
        chkFallidas.setOnCheckedChangeListener { _, _ -> aplicarFiltros() }
        lista.setOnItemClickListener { _, _, posicion, _ ->
            val evento = adaptador.getItem(posicion)
            if (evento != null) mostrarOpciones(evento)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    gestor.eventos(sesionId).collect { eventos ->
                        todos = eventos
                        aplicarFiltros()
                    }
                }
                launch {
                    while (true) {
                        refrescarCabecera()
                        delay(2000)
                    }
                }
            }
        }
    }

    private fun analizarAhora(boton: MaterialButton) {
        boton.isEnabled = false
        boton.setText(R.string.analizando)
        lifecycleScope.launch {
            gestor.analizar(sesionId)
            boton.isEnabled = true
            boton.setText(R.string.btn_analizar)
            abrir(HallazgosActivity::class.java)
        }
    }

    private fun abrir(destino: Class<*>) {
        val intent = Intent(this, destino)
        intent.putExtra(EXTRA_SESION, sesionId)
        startActivity(intent)
    }

    private fun esFallida(e: DnsEventEntity): Boolean {
        return e.respuesta.startsWith("sin respuesta") ||
            e.respuesta.startsWith("error") ||
            e.respuesta == "NXDOMAIN"
    }

    private fun aplicarFiltros() {
        val texto = etBuscar.text.toString().trim().lowercase()
        val soloFallidas = chkFallidas.isChecked
        val fd = filtroDominio
        val fa = filtroApp

        val filtrados = todos.filter { e ->
            (fd == null || e.dominio == fd) &&
                (fa == null || e.app == fa) &&
                (texto.isEmpty() || e.dominio.contains(texto) || e.app.lowercase().contains(texto)) &&
                (!soloFallidas || esFallida(e))
        }
        adaptador.actualizar(filtrados)

        val hayFiltro = fd != null || fa != null || texto.isNotEmpty() || soloFallidas
        if (hayFiltro) {
            val partes = ArrayList<String>()
            if (fd != null) partes.add("dominio " + fd)
            if (fa != null) partes.add("app " + fa)
            if (texto.isNotEmpty()) partes.add("texto «" + texto + "»")
            if (soloFallidas) partes.add("solo fallidas")
            val dominios = filtrados.map { it.dominio }.toSet().size
            val apps = filtrados.map { it.app }.toSet().size
            var resumen = "Filtro: " + partes.joinToString(" · ") + "\n" +
                filtrados.size + " consultas · " + dominios + " dominios · " + apps + " apps"
            if (filtrados.isNotEmpty()) {
                val primero = filtrados.minOf { it.ts }
                val ultimo = filtrados.maxOf { it.ts }
                resumen += " · de " + Formato.hora(primero) + " a " + Formato.hora(ultimo)
            }
            detFiltro.text = resumen
            detFiltro.visibility = View.VISIBLE
        } else {
            detFiltro.visibility = View.GONE
        }

        if (filtrados.isEmpty()) {
            detVacio.setText(if (todos.isEmpty()) R.string.sin_eventos else R.string.sin_resultados)
            detVacio.visibility = View.VISIBLE
        } else {
            detVacio.visibility = View.GONE
        }
    }

    private fun quitarFiltros() {
        filtroDominio = null
        filtroApp = null
        etBuscar.setText("")
        chkFallidas.isChecked = false
        aplicarFiltros()
    }

    private fun mostrarOpciones(e: DnsEventEntity) {
        val n = todos.count { it.dominio == e.dominio }
        val m = todos.count { it.app == e.app }
        val opciones = arrayOf(
            "Todas las consultas a " + e.dominio + " (" + n + ")",
            "Solo la app " + e.app + " (" + m + ")"
        )
        AlertDialog.Builder(this)
            .setTitle(e.dominio)
            .setItems(opciones) { _, cual ->
                if (cual == 0) {
                    filtroDominio = e.dominio
                } else {
                    filtroApp = e.app
                }
                aplicarFiltros()
            }
            .show()
    }

    private fun elegirApp() {
        val conteo = todos.groupingBy { it.app }.eachCount().entries
            .sortedByDescending { it.value }.take(100)
        if (conteo.isEmpty()) return
        val etiquetas = conteo.map { it.key + " — " + it.value }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.elegir_app)
            .setItems(etiquetas) { _, cual ->
                filtroApp = conteo[cual].key
                aplicarFiltros()
            }
            .show()
    }

    private fun elegirDominio() {
        val conteo = todos.groupingBy { it.dominio }.eachCount().entries
            .sortedByDescending { it.value }.take(100)
        if (conteo.isEmpty()) return
        val etiquetas = conteo.map { it.key + " — " + it.value }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.elegir_dominio)
            .setItems(etiquetas) { _, cual ->
                filtroDominio = conteo[cual].key
                aplicarFiltros()
            }
            .show()
    }

    private suspend fun refrescarCabecera() {
        val sesion = gestor.sesion(sesionId)
        if (sesion == null) {
            finish()
            return
        }
        val c = gestor.contadores(sesionId)
        val fin = sesion.fin
        val duracion = Formato.duracion((fin ?: System.currentTimeMillis()) - sesion.inicio)
        val estado = if (fin == null) "en curso" else "cerrada"
        val captura = if (SessionManager.vpnDe(sesion.configuracion)) {
            "sí (DNS: " + SessionManager.dnsDe(sesion.configuracion) + ")"
        } else {
            "no"
        }
        detTitulo.text = "Sesión #" + sesion.id
        detResumen.text = "Inicio: " + Formato.fecha(sesion.inicio) +
            "\nEstado: " + estado + " · duración " + duracion +
            "\nCaptura DNS: " + captura +
            "\nConsultas DNS: " + c.consultas + " · dominios únicos: " + c.dominios + " · apps: " + c.apps +
            "\nOtros eventos: sistema " + c.sistema + " · cámara/micrófono " + c.sensores + " · pantalla " + c.pantalla +
            "\nHallazgos: " + c.hallazgos + " (altos o críticos: " + c.hallazgosAltos + ")" +
            (if (SessionManager.crudosBorradosDe(sesion.configuracion) > 0L) {
                "\nDatos crudos: borrados el " + Formato.fecha(SessionManager.crudosBorradosDe(sesion.configuracion))
            } else {
                ""
            })
        btnBorrar.isEnabled = fin != null
    }

    private fun confirmarBorrado() {
        AlertDialog.Builder(this)
            .setTitle(R.string.borrar_titulo)
            .setMessage(R.string.borrar_mensaje)
            .setPositiveButton(R.string.borrar) { _, _ ->
                lifecycleScope.launch {
                    gestor.borrarSesion(sesionId)
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_SESION = "sesion_id"
    }
}
