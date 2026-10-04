package com.ejemplo.chat.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ejemplo.chat.R
import com.ejemplo.chat.ia.MotorIA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Chat que usa MotorIA. Primer arranque: elegir modelo, confirmar, descargar y
// cargar. Guarda el historial en filesDir y lo restaura al reabrir. Con los
// modelos Gemma 4 se puede adjuntar una imagen a cada mensaje.
class ChatActivity : AppCompatActivity() {

    private lateinit var motor: MotorIA
    private lateinit var tvEstado: TextView
    private lateinit var tvChat: TextView
    private lateinit var tvAdjunto: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progreso: ProgressBar
    private lateinit var etMensaje: EditText
    private lateinit var btnEnviar: Button
    private lateinit var btnImagen: Button
    private lateinit var btnNuevo: Button
    private val mensajes = mutableListOf<Pair<String, String>>()
    private var imagenRuta: String? = null
    private var generando = false

    private val selectorImagen = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) adjuntar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        motor = MotorIA(this)
        tvEstado = findViewById(R.id.tvEstado)
        tvChat = findViewById(R.id.tvChat)
        tvAdjunto = findViewById(R.id.tvAdjunto)
        scroll = findViewById(R.id.scrollChat)
        progreso = findViewById(R.id.progreso)
        etMensaje = findViewById(R.id.etMensaje)
        btnEnviar = findViewById(R.id.btnEnviar)
        btnImagen = findViewById(R.id.btnImagen)
        btnNuevo = findViewById(R.id.btnNuevo)
        btnEnviar.isEnabled = false
        btnEnviar.setOnClickListener { enviar() }
        btnImagen.setOnClickListener { selectorImagen.launch("image/*") }
        btnNuevo.setOnClickListener { nueva() }
        tvAdjunto.setOnClickListener { quitarImagen() }

        cargarHistorial()
        render(null)

        val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
        val guardado = MotorIA.MODELOS.firstOrNull { it.id == prefs.getString("modelo", null) }
        if (guardado != null && motor.modeloDescargado(guardado)) preparar(guardado) else elegirModelo()
    }

    private fun archivoHistorial(): File = File(filesDir, "chat_historial.json")

    private fun cargarHistorial() {
        try {
            val arr = JSONArray(archivoHistorial().readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                mensajes.add(Pair(o.getString("r"), o.getString("t")))
            }
        } catch (e: Exception) { }
    }

    private fun guardarHistorial() {
        try {
            val arr = JSONArray()
            mensajes.forEach { arr.put(JSONObject().put("r", it.first).put("t", it.second)) }
            archivoHistorial().writeText(arr.toString())
        } catch (e: Exception) { }
    }

    private fun render(parcial: String?) {
        val sb = StringBuilder()
        mensajes.forEach { sb.append(if (it.first == "u") "Tu: " else "IA: ").append(it.second).append("\n\n") }
        if (parcial != null) sb.append("IA: ").append(parcial)
        tvChat.text = sb.toString()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
        btnImagen.visibility = if (m.vision) View.VISIBLE else View.GONE
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
                motor.inicializar(m, mensajes.toList())
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

    private fun adjuntar(uri: Uri) {
        lifecycleScope.launch {
            val ruta = withContext(Dispatchers.IO) { try { reducirImagen(uri) } catch (e: Exception) { null } }
            if (ruta == null) {
                tvEstado.text = "No se pudo leer la imagen"
                return@launch
            }
            imagenRuta = ruta
            tvAdjunto.text = "Imagen adjunta (toca para quitar)"
            tvAdjunto.visibility = View.VISIBLE
        }
    }

    private fun quitarImagen() {
        imagenRuta = null
        tvAdjunto.visibility = View.GONE
    }

    // Reduce la imagen a 1024 px como maximo y la guarda como JPEG en cache.
    private fun reducirImagen(uri: Uri): String? {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
        var muestra = 1
        while (maxOf(limites.outWidth, limites.outHeight) / muestra > 1024) muestra *= 2
        val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opciones) } ?: return null
        val f = File(cacheDir, "adjunto.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }

    private fun nueva() {
        if (generando) return
        mensajes.clear()
        archivoHistorial().delete()
        quitarImagen()
        try { motor.nuevaConversacion() } catch (e: Exception) { }
        render(null)
        tvEstado.text = "Conversacion nueva"
    }

    private fun enviar() {
        val texto = etMensaje.text.toString().trim()
        val ruta = imagenRuta
        if ((texto.isEmpty() && ruta == null) || generando) return
        val prompt = if (texto.isEmpty()) "Describe esta imagen." else texto
        etMensaje.setText("")
        quitarImagen()
        generando = true
        btnEnviar.isEnabled = false
        mensajes.add(Pair("u", (if (ruta != null) "[imagen] " else "") + prompt))
        render(null)
        tvEstado.text = "Generando..."
        lifecycleScope.launch {
            val respuesta = StringBuilder()
            try {
                motor.generar(prompt, ruta).collect { trozo ->
                    respuesta.append(trozo)
                    render(respuesta.toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                respuesta.append("[Error: " + (e.message ?: e.javaClass.simpleName) + "]")
            }
            mensajes.add(Pair("m", respuesta.toString()))
            guardarHistorial()
            render(null)
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
