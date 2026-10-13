package com.vigia.app

import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.vigia.app.core.SessionManager
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
    private lateinit var btnBorrar: MaterialButton
    private var sesionId = -1L

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
        btnBorrar = findViewById(R.id.btnBorrar)
        val btnVolver = findViewById<MaterialButton>(R.id.btnVolver)
        val lista = findViewById<ListView>(R.id.listaEventos)

        adaptador = EventosAdapter(this)
        lista.adapter = adaptador

        btnVolver.setOnClickListener { finish() }
        btnBorrar.setOnClickListener { confirmarBorrado() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    gestor.eventos(sesionId).collect { eventos ->
                        adaptador.actualizar(eventos)
                        detVacio.visibility = if (eventos.isEmpty()) View.VISIBLE else View.GONE
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
        val captura = if (SessionManager.vpnDe(sesion.configuracion)) "sí" else "no"
        detTitulo.text = "Sesión #" + sesion.id
        detResumen.text = "Inicio: " + Formato.fecha(sesion.inicio) +
            "\nEstado: " + estado + " · duración " + duracion +
            "\nCaptura DNS: " + captura +
            "\nConsultas: " + c.consultas + " · dominios únicos: " + c.dominios + " · apps: " + c.apps
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
