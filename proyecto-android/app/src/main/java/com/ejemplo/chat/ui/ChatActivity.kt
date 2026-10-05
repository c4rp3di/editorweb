package com.ejemplo.chat.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.ejemplo.chat.R
import com.ejemplo.chat.ia.MotorIA
import com.ejemplo.chat.ia.ModelosImagen
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ChatActivity : AppCompatActivity() {
    private lateinit var motor: MotorIA
    private lateinit var drawer: DrawerLayout
    private lateinit var listaConversaciones: LinearLayout
    private lateinit var tvEstado: TextView
    private lateinit var tvAdjunto: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progreso: ProgressBar
    private lateinit var etMensaje: EditText
    private lateinit var btnEnviar: MaterialButton
    private lateinit var btnImagen: MaterialButton
    private lateinit var btnModelo: MaterialButton
    private lateinit var btnModeloImagen: MaterialButton
    private lateinit var contenedor: LinearLayout
    private lateinit var imagenes: ModelosImagen

    private val mensajes = mutableListOf<Pair<String, String>>()
    private var imagenRuta: String? = null
    private var generando = false
    private var modeloActual: MotorIA.Modelo? = null
    private var sesionId = "actual"
    private var tituloSesion = "Nueva conversación"

    private val selectorImagen = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) adjuntar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        motor = MotorIA(this)
        imagenes = ModelosImagen(this)

        drawer = findViewById(R.id.drawer)
        listaConversaciones = findViewById(R.id.listaConversaciones)
        tvEstado = findViewById(R.id.tvEstado)
        tvAdjunto = findViewById(R.id.tvAdjunto)
        scroll = findViewById(R.id.scrollChat)
        progreso = findViewById(R.id.progreso)
        etMensaje = findViewById(R.id.etMensaje)
        btnEnviar = findViewById(R.id.btnEnviar)
        btnImagen = findViewById(R.id.btnImagen)
        btnModelo = findViewById(R.id.btnModelo)
        btnModeloImagen = findViewById(R.id.btnModeloImagen)
        contenedor = findViewById(R.id.contenedorMensajes)

        findViewById<MaterialButton>(R.id.btnNuevo).setOnClickListener { nueva() }
        findViewById<MaterialButton>(R.id.btnSidebar).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<MaterialButton>(R.id.btnCerrarSidebar).setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
        findViewById<MaterialButton>(R.id.btnNuevaSidebar).setOnClickListener { nueva() }
        btnEnviar.setOnClickListener { enviar() }
        btnImagen.setOnClickListener { selectorImagen.launch("image/*") }
        btnModelo.setOnClickListener { elegirModelo() }
        btnModeloImagen.setOnClickListener { elegirModeloImagen() }
        tvAdjunto.setOnClickListener { quitarImagen() }
        etMensaje.setOnEditorActionListener { _, actionId, _ -> if (actionId != 0) enviar() else false }
        btnEnviar.isEnabled = false

        cargarSesionActual()
        render(null)
        renderSidebar()

        val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
        val guardado = MotorIA.MODELOS.firstOrNull { it.id == prefs.getString("modelo", null) }
        if (guardado != null && motor.modeloDescargado(guardado)) preparar(guardado) else {
            tvEstado.text = "Elige un modelo para descargar y usarlo"
            elegirModelo()
        }
    }

    private fun carpetaChats() = File(filesDir, "conversaciones").apply { mkdirs() }
    private fun archivoSesion(id: String) = File(carpetaChats(), "$id.json")

    private fun cargarSesionActual() {
        val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
        sesionId = prefs.getString("sesion", null) ?: "actual"
        if (!archivoSesion(sesionId).exists() && File(filesDir, "chat_historial.json").exists()) {
            try {
                archivoSesion(sesionId).writeText(File(filesDir, "chat_historial.json").readText())
            } catch (_: Exception) {}
        }
        cargarSesion(sesionId)
    }

    private fun cargarSesion(id: String) {
        mensajes.clear()
        sesionId = id
        try {
            val arr = JSONArray(archivoSesion(id).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                mensajes.add(o.getString("r") to o.getString("t"))
            }
        } catch (_: Exception) {}
        tituloSesion = mensajes.firstOrNull { it.first == "u" }?.second
            ?.replace(Regex("\\s+"), " ")?.take(34) ?: "Nueva conversación"
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("sesion", id).apply()
    }

    private fun guardarSesion() {
        try {
            val arr = JSONArray()
            mensajes.forEach { arr.put(JSONObject().put("r", it.first).put("t", it.second)) }
            archivoSesion(sesionId).writeText(arr.toString())
            renderSidebar()
        } catch (_: Exception) {}
    }

    private fun renderSidebar() {
        listaConversaciones.removeAllViews()
        val files = carpetaChats().listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        files.forEach { file ->
            val id = file.nameWithoutExtension
            val title = try {
                val arr = JSONArray(file.readText())
                if (arr.length() > 0) arr.getJSONObject(0).getString("t").replace(Regex("\\s+"), " ").take(34)
                else "Nueva conversación"
            } catch (_: Exception) { "Nueva conversación" }

            val b = MaterialButton(this).apply {
                text = if (id == sesionId) "●  $title" else "   $title"
                textAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener {
                    if (generando) return@setOnClickListener
                    cargarSesion(id)
                    render(null)
                    drawer.closeDrawer(GravityCompat.START)
                    if (modeloActual != null) motor.nuevaConversacion(mensajes.toList())
                }
            }
            listaConversaciones.addView(b, LinearLayout.LayoutParams(-1, 48).apply { setMargins(0, 2, 0, 2) })
        }
        if (files.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Todavía no hay conversaciones."
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(12, 18, 12, 18)
            }
            listaConversaciones.addView(empty)
        }
    }

    private fun render(parcial: String?) {
        contenedor.removeAllViews()
        if (mensajes.isEmpty() && parcial == null) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(32, 80, 32, 32)
            }
            val title = TextView(this).apply {
                text = "¿En qué puedo ayudarte?"
                textSize = 27f
                setTextColor(resolveTextColor())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val subtitle = TextView(this).apply {
                text = "Tu asistente local. Privado, rápido y sin enviar tus chats a la nube."
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 0)
            }
            empty.addView(title)
            empty.addView(subtitle)
            contenedor.addView(empty)
        }
        mensajes.forEachIndexed { index, pair -> agregarBurbuja(pair.first, pair.second, false, index) }
        if (parcial != null) agregarBurbuja("m", parcial, true, mensajes.size)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resolveTextColor(): Int = if ((resources.configuration.uiMode and 0x30) == 0x20) Color.WHITE else Color.rgb(24,24,27)

    private fun agregarBurbuja(rol: String, texto: String, streaming: Boolean = false, index: Int = -1) {
        val fila = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(6, 6, 6, 2) }
            orientation = LinearLayout.VERTICAL
            gravity = if (rol == "u") Gravity.END else Gravity.START
        }
        val bubble = TextView(this).apply {
            text = markdownBasico(texto)
            textSize = 15.5f
            setTextColor(if (rol == "u") Color.WHITE else resolveTextColor())
            setPadding(16, 12, 16, 12)
            setBackgroundResource(if (rol == "u") R.drawable.bg_user else R.drawable.bg_model)
            maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            textIsSelectable = true
            setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        }
        fila.addView(bubble)

        if (rol == "m" && !streaming) {
            val actions = LinearLayout(this).apply { gravity = Gravity.START }
            val copy = MaterialButton(this).apply {
                text = "Copiar"
                textAllCaps = false
                minHeight = 34
                setOnClickListener { copiar(texto) }
            }
            actions.addView(copy)
            if (index == mensajes.lastIndex) {
                val regen = MaterialButton(this).apply {
                        text = "Regenerar"
                    textAllCaps = false
                    minHeight = 34
                    setOnClickListener { regenerarUltima() }
                }
                actions.addView(regen)
            }
            fila.addView(actions)
        }
        contenedor.addView(fila)
    }

    private fun markdownBasico(texto: String): SpannableString {
        val s = SpannableString(texto)
        Regex("\\*\\*(.+?)\\*\\*").findAll(texto).forEach {
            s.setSpan(StyleSpan(Typeface.BOLD), it.range.first, it.range.last + 1, 0)
        }
        return s
    }

    private fun copiar(texto: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("respuesta", texto))
        Toast.makeText(this, "Respuesta copiada", Toast.LENGTH_SHORT).show()
    }

    private fun regenerarUltima() {
        if (generando || mensajes.size < 2 || modeloActual == null) return
        val ultimoUsuario = mensajes.indexOfLast { it.first == "u" }
        if (ultimoUsuario < 0) return
        while (mensajes.size > ultimoUsuario + 1) mensajes.removeAt(mensajes.lastIndex)
        val prompt = mensajes[ultimoUsuario].second.removePrefix("📷 ").trim()
        generarDesde(prompt, null, guardar = true)
    }

    private fun elegirModelo() {
        if (generando) return
        val items = MotorIA.MODELOS.map { m ->
            val estado = when {
                modeloActual?.id == m.id -> "✓ activo"
                motor.modeloDescargado(m) -> "✓ descargado"
                else -> "${m.tamanoMb} MB"
            }
            "${m.nombre}${if (m.vision) " · visión" else ""}\n$estado"
        }.toTypedArray()
        var elegido = MotorIA.MODELOS.indexOfFirst { it.id == modeloActual?.id }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Modelo de IA")
            .setSingleChoiceItems(items, elegido) { _, which -> elegido = which }
            .setNeutralButton("Descargar") { _, _ ->
                val m = MotorIA.MODELOS[elegido]
                confirmarDescarga(m)
            }
            .setPositiveButton("Usar") { _, _ ->
                val m = MotorIA.MODELOS[elegido]
                if (!motor.modeloDescargado(m)) {
                    Toast.makeText(this, "Primero descarga el modelo", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("modelo", m.id).apply()
                preparar(m)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarDescarga(m: MotorIA.Modelo) {
        val espacio = motor.espacioLibreMb()
        val texto = "${m.nombre} ocupa aproximadamente ${m.tamanoMb} MB.\n\nEspacio libre: ${espacio} MB.\n\nLa descarga solo empieza si tú la confirmas."
        AlertDialog.Builder(this)
            .setTitle("Descargar modelo")
            .setMessage(texto)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Descargar") { _, _ ->
                lifecycleScope.launch {
                    try {
                        progreso.visibility = View.VISIBLE
                        progreso.isIndeterminate = false
                        tvEstado.text = "Descargando ${m.nombre}…"
                        motor.descargarModelo(m) { p ->
                            progreso.progress = p
                            tvEstado.text = "Descargando ${m.nombre} · $p%"
                        }
                        progreso.visibility = View.GONE
                        Toast.makeText(this@ChatActivity, "${m.nombre} descargado", Toast.LENGTH_SHORT).show()
                        elegirModelo()
                    } catch (e: Exception) {
                        progreso.visibility = View.GONE
                        tvEstado.text = "Error de descarga"
                        Toast.makeText(this@ChatActivity, e.message ?: "No se pudo descargar", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun elegirModeloImagen() {
        val items = ModelosImagen.MODELOS.map { m ->
            val listo = imagenes.descargado(m)
            "${m.nombre} · ${m.resolucion}\n" +
                if (listo) "✓ disponible" else "~${m.tamanoGb} GB · descarga manual"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Modelos de imagen")
            .setItems(items) { _, which ->
                val m = ModelosImagen.MODELOS[which]
                val listo = imagenes.descargado(m)
                val estado = if (listo) "✓ descargado" else "${m.tamanoGb} GB · descarga manual"
                AlertDialog.Builder(this)
                    .setTitle(m.nombre)
                    .setMessage("${m.descripcion}\n\n$estado\n\nLa app nunca descarga modelos automáticamente.")
                    .setNegativeButton("Cerrar", null)
                    .setPositiveButton(if (listo) "Usar" else "Descargar") { _, _ ->
                        if (listo) {
                            Toast.makeText(this, "${m.nombre} seleccionado para imagen", Toast.LENGTH_SHORT).show()
                        } else {
                            lifecycleScope.launch {
                                try {
                                    progreso.visibility = View.VISIBLE
                                    progreso.isIndeterminate = false
                                    imagenes.descargar(m) { p, archivo ->
                                        progreso.progress = p
                                        tvEstado.text = "Imagen · $p% · $archivo"
                                    }
                                    progreso.visibility = View.GONE
                                    tvEstado.text = "Modelo de imagen descargado"
                                    Toast.makeText(this@ChatActivity, "${m.nombre} descargado", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    progreso.visibility = View.GONE
                                    Toast.makeText(this@ChatActivity, e.message ?: "Error de descarga", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    .show()
            }
            .show()
    }

    private fun preparar(m: MotorIA.Modelo) {
        modeloActual = m
        btnModelo.text = m.nombre
        btnModeloImagen.visibility = View.VISIBLE
        btnImagen.visibility = if (m.vision) View.VISIBLE else View.GONE
        btnEnviar.isEnabled = false
        lifecycleScope.launch {
            try {
                if (!motor.modeloDescargado(m)) {
                    progreso.visibility = View.GONE
                    tvEstado.text = "Modelo no descargado"
                    Toast.makeText(this@ChatActivity, "Descarga primero este modelo desde el selector", Toast.LENGTH_LONG).show()
                    return@launch
                }
                progreso.visibility = View.VISIBLE
                progreso.isIndeterminate = true
                tvEstado.text = "Cargando ${m.nombre}…"
                motor.inicializar(m, mensajes.toList())
                progreso.visibility = View.GONE
                tvEstado.text = "Listo · privado y sin conexión"
                btnEnviar.isEnabled = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                progreso.visibility = View.GONE
                tvEstado.text = "No se pudo cargar el modelo"
                Toast.makeText(this@ChatActivity, e.message ?: "Error desconocido", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun adjuntar(uri: Uri) {
        lifecycleScope.launch {
            val ruta = withContext(Dispatchers.IO) { try { reducirImagen(uri) } catch (_: Exception) { null } }
            if (ruta == null) { tvEstado.text = "No se pudo leer la imagen"; return@launch }
            imagenRuta = ruta
            tvAdjunto.text = "📎 Imagen adjunta · toca para quitar"
            tvAdjunto.visibility = View.VISIBLE
        }
    }

    private fun quitarImagen() { imagenRuta = null; tvAdjunto.visibility = View.GONE }

    private fun reducirImagen(uri: Uri): String? {
        val limites = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, limites) }
        var muestra = 1
        while (maxOf(limites.outWidth, limites.outHeight) / muestra > 1024) muestra *= 2
        val opciones = android.graphics.BitmapFactory.Options().apply { inSampleSize = muestra }
        val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opciones) } ?: return null
        val f = File(cacheDir, "adjunto.jpg")
        java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }

    private fun nueva() {
        if (generando) return
        if (mensajes.isNotEmpty()) guardarSesion()
        sesionId = UUID.randomUUID().toString()
        mensajes.clear()
        tituloSesion = "Nueva conversación"
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("sesion", sesionId).apply()
        quitarImagen()
        motor.nuevaConversacion()
        render(null)
        renderSidebar()
        drawer.closeDrawer(GravityCompat.START)
        tvEstado.text = "Nueva conversación"
        etMensaje.requestFocus()
    }

    private fun enviar(): Boolean {
        val texto = etMensaje.text.toString().trim()
        val ruta = imagenRuta
        if ((texto.isEmpty() && ruta == null) || generando || modeloActual == null) return false
        val prompt = if (texto.isEmpty()) "Describe esta imagen." else texto
        etMensaje.setText("")
        quitarImagen()
        mensajes.add("u" to (if (ruta != null) "📷 $prompt" else prompt))
        render(null)
        generarDesde(prompt, ruta, guardar = true)
        return true
    }

    private fun generarDesde(prompt: String, ruta: String?, guardar: Boolean) {
        generando = true
        btnEnviar.isEnabled = false
        tvEstado.text = "Generando · ${modeloActual?.nombre ?: ""}"
        lifecycleScope.launch {
            val respuesta = StringBuilder()
            try {
                motor.generar(prompt, ruta).collect { trozo ->
                    respuesta.append(trozo)
                    render(respuesta.toString())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { respuesta.append("Error: ${e.message ?: e.javaClass.simpleName}") }
            mensajes.add("m" to respuesta.toString())
            if (guardar) guardarSesion()
            render(null)
            tvEstado.text = "Listo · ${modeloActual?.nombre ?: ""}"
            generando = false
            btnEnviar.isEnabled = true
        }
    }

    override fun onDestroy() {
        motor.liberar()
        super.onDestroy()
    }
}
