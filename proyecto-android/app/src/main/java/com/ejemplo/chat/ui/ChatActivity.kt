package com.ejemplo.chat.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ejemplo.chat.R
import com.ejemplo.chat.ia.MotorIA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Pantalla de chat que usa MotorIA. Primer arranque: elegir modelo, confirmar,
// descargar y cargar. Despues funciona sin conexion.
class ChatActivity : AppCompatActivity() {

    private lateinit var motor: MotorIA
    private lateinit var tvEstado: TextView
    private lateinit var tvChat: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progreso: ProgressBar
    private lateinit var etMensaje: EditText
    private lateinit var btnEnviar: Button
    private val historial = StringBuilder()
    private var generando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        motor = MotorIA(this)
        tvEstado = findViewById(R.id.tvEstado)
        tvChat = findViewById(R.id.tvChat)
        scroll = findViewById(R.id.scrollChat)
        progreso = findViewById(R.id.progreso)
        etMensaje = findViewById(R.id.etMensaje)
        btnEnviar = findViewById(R.id.btnEnviar)
        btnEnviar.isEnabled = false
        btnEnviar.setOnClickListener { enviar() }

        val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
        val guardado = MotorIA.MODELOS.firstOrNull { it.id == prefs.getString("modelo", null) }
        if (guardado != null && motor.modeloDescargado(guardado)) preparar(guardado) else elegirModelo()
    }

    private fun elegirModelo() {
        var elegido = 0
        val nombres = MotorIA.MODELOS.map { it.nombre + " (~" + it.tamanoMb + " MB)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elige el modelo de IA")
            .setSingleChoiceItems(nombres, 0) { _, which -> elegido = which }
            .setPositiveButton("Descargar") { _, _ ->
                val m = MotorIA.MODELOS[elegido]
                getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("modelo", m.id).apply()
                preparar(m)
            }
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun preparar(m: MotorIA.Modelo) {
        lifecycleScope.launch {
            try {
                if (!motor.modeloDescargado(m)) {
                    progreso.visibility = View.VISIBLE
                    tvEstado.text = "Descargando " + m.nombre + " (0%)"
                    motor.descargarModelo(m) { p ->
                        progreso.progress = p
                        tvEstado.text = "Descargando " + m.nombre + " (" + p + "%)"
                    }
                }
                progreso.isIndeterminate = true
                tvEstado.text = "Cargando modelo..."
                motor.inicializar(m)
                progreso.visibility = View.GONE
                tvEstado.text = "Listo (sin conexion): " + m.nombre
                btnEnviar.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progreso.visibility = View.GONE
                tvEstado.text = "Error: " + (e.message ?: e.javaClass.simpleName) + ". Reabre el chat para reintentar."
            }
        }
    }

    private fun enviar() {
        val texto = etMensaje.text.toString().trim()
        if (texto.isEmpty() || generando) return
        etMensaje.setText("")
        generando = true
        btnEnviar.isEnabled = false
        historial.append("Tu: ").append(texto).append("\n\n")
        val base = historial.toString()
        val respuesta = StringBuilder()
        tvEstado.text = "Generando..."
        lifecycleScope.launch {
            try {
                motor.generar(texto).collect { trozo ->
                    respuesta.append(trozo)
                    tvChat.text = base + "IA: " + respuesta
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                respuesta.append("[Error: " + (e.message ?: e.javaClass.simpleName) + "]")
            }
            historial.append("IA: ").append(respuesta).append("\n\n")
            tvChat.text = historial.toString()
            tvEstado.text = "Listo"
            generando = false
            btnEnviar.isEnabled = true
        }
    }

    override fun onDestroy() {
        motor.liberar()
        super.onDestroy()
    }
}
