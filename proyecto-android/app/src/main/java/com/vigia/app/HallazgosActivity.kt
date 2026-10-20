package com.vigia.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.vigia.app.analyze.Reglas
import com.vigia.app.analyze.Severidad
import com.vigia.app.core.SessionManager
import com.vigia.app.store.FindingEntity
import com.vigia.app.ui.HallazgosAdapter
import com.vigia.app.util.Formato
import kotlinx.coroutines.launch
import org.json.JSONArray

/** Hallazgos del análisis de una sesión, ordenados por gravedad, con explicación en lenguaje claro. */
class HallazgosActivity : AppCompatActivity() {

    private lateinit var gestor: SessionManager
    private lateinit var adaptador: HallazgosAdapter
    private lateinit var hallResumen: TextView
    private lateinit var hallVacio: TextView
    private lateinit var btnAnalizar: MaterialButton
    private var sesionId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hallazgos)

        sesionId = intent.getLongExtra(SessionDetailActivity.EXTRA_SESION, -1L)
        if (sesionId < 0) {
            finish()
            return
        }
        gestor = SessionManager.obtener(applicationContext)

        hallResumen = findViewById(R.id.hallResumen)
        hallVacio = findViewById(R.id.hallVacio)
        btnAnalizar = findViewById(R.id.btnAnalizar)
        val lista = findViewById<ListView>(R.id.listaHallazgos)
        adaptador = HallazgosAdapter(this)
        lista.adapter = adaptador

        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }
        btnAnalizar.setOnClickListener { analizar() }
        lista.setOnItemClickListener { _, _, posicion, _ ->
            val hallazgo = adaptador.getItem(posicion)
            if (hallazgo != null) mostrarDetalle(hallazgo)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gestor.hallazgos(sesionId).collect { filas ->
                    adaptador.actualizar(filas)
                    resumir(filas)
                }
            }
        }
    }

    private fun resumir(filas: List<FindingEntity>) {
        val criticas = filas.count { it.severidad == Severidad.CRITICA }
        val altas = filas.count { it.severidad == Severidad.ALTA }
        val medias = filas.count { it.severidad == Severidad.MEDIA }
        val bajas = filas.count { it.severidad == Severidad.BAJA }
        val info = filas.count { it.severidad == Severidad.INFO }
        hallResumen.text = "Críticas: " + criticas + " · Altas: " + altas + " · Medias: " + medias +
            " · Bajas: " + bajas + " · Informativas: " + info
        hallVacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun analizar() {
        btnAnalizar.isEnabled = false
        btnAnalizar.setText(R.string.analizando)
        lifecycleScope.launch {
            val n = gestor.analizar(sesionId)
            btnAnalizar.isEnabled = true
            btnAnalizar.setText(R.string.analizar_de_nuevo)
            Toast.makeText(this@HallazgosActivity, getString(R.string.analisis_listo, n), Toast.LENGTH_LONG).show()
        }
    }

    private fun mostrarDetalle(f: FindingEntity) {
        val regla = Reglas.porId(f.regla)
        val sb = StringBuilder()
        sb.append(Severidad.etiqueta(f.severidad)).append(" · módulo ").append(f.modulo)
            .append(" · confianza ").append(f.confianza).append("%\n")
        sb.append(Formato.fecha(f.ts)).append(" (").append(Formato.hora(f.ts)).append(")\n")
        if (f.app.isNotEmpty()) sb.append("App: ").append(f.app).append('\n')
        sb.append('\n').append(f.descripcion).append("\n\n")
        if (regla != null) {
            sb.append("Qué es: ").append(regla.queEs).append("\n\n")
            sb.append("Por qué importa: ").append(regla.porQue).append("\n\n")
            sb.append("Qué hacer: ").append(regla.queHacer).append("\n\n")
        }
        val pruebas = try {
            JSONArray(f.evidenciaIds).length()
        } catch (e: Exception) {
            0
        }
        sb.append("Pruebas guardadas: ").append(pruebas).append(". ")
        sb.append("Esto es anómalo, no una prueba de infección: considera investigar.")

        AlertDialog.Builder(this)
            .setTitle(f.titulo)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ver_en_linea) { _, _ ->
                val intent = Intent(this, LineaTiempoActivity::class.java)
                intent.putExtra(SessionDetailActivity.EXTRA_SESION, sesionId)
                intent.putExtra(LineaTiempoActivity.EXTRA_CENTRO, f.ts)
                startActivity(intent)
            }
            .setNegativeButton(R.string.cerrar, null)
            .show()
    }
}
