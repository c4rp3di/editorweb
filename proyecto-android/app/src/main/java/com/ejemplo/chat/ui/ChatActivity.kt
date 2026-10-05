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
import com.ejemplo.chat.ia.flux.Flux2KleinGenerator
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
    private lateinit var contenedor: LinearLayout
    private lateinit var imagenes: ModelosImagen

    private val mensajes = mutableListOf<Pair<String, String>>()
    private var imagenRuta: String? = null
    private var generando = false
    private var modeloActual: MotorIA.Modelo? = null
    private var modeloImagenActual: ModelosImagen.ModeloImagen? = null
    private var fluxGenerator: Flux2KleinGenerator? = null
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
        contenedor = findViewById(R.id.contenedorMensajes)

        findViewById<MaterialButton>(R.id.btnNuevo).setOnClickListener { nueva() }
        findViewById<MaterialButton>(R.id.btnSidebar).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<MaterialButton>(R.id.btnCerrarSidebar).setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
        findViewById<MaterialButton>(R.id.btnNuevaSidebar).setOnClickListener { nueva() }
        btnEnviar.setOnClickListener { enviar() }
        btnImagen.setOnClickListener { selectorImagen.launch("image/*") }
        btnModelo.setOnClickListener { elegirModelo() }
        tvAdjunto.setOnClickListener { quitarImagen() }
        etMensaje.setOnEditorActionListener { _, actionId, _ -> if (actionId != 0) enviar() else false }
        btnEnviar.isEnabled = false

        // Restoring a previous image-model selection is local state only: it never downloads anything.
        val imageId = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
            .getString("modelo_imagen", null)
        modeloImagenActual = ModelosImagen.MODELOS.firstOrNull { it.id == imageId && imagenes.descargado(it) }
        actualizarBotonEnvio()

        cargarSesionActual()
        render(null)
        renderSidebar()

        val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
        val guardado = MotorIA.MODELOS.firstOrNull { it.id == prefs.getString("modelo", null) }
        val imagenGuardada = modeloImagenActual
        if (imagenGuardada != null) {
            activarModeloImagen(imagenGuardada)
        } else if (guardado != null && motor.modeloDescargado(guardado)) preparar(guardado) else {
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
                setAllCaps(false)
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
        if (rol == "m" && texto.startsWith("[[IMAGE]]")) {
            val path = texto.removePrefix("[[IMAGE]]")
            val image = ImageView(this).apply {
                setImageURI(Uri.fromFile(File(path)))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
                setPadding(4, 4, 4, 4)
                contentDescription = "Imagen generada localmente"
                setOnClickListener {
                    Toast.makeText(this@ChatActivity, "Imagen: $path", Toast.LENGTH_SHORT).show()
                }
            }
            fila.addView(image)
        } else {
            val bubble = TextView(this).apply {
                text = markdownBasico(texto)
                textSize = 15.5f
                setTextColor(if (rol == "u") Color.WHITE else resolveTextColor())
                setPadding(16, 12, 16, 12)
                setBackgroundResource(if (rol == "u") R.drawable.bg_user else R.drawable.bg_model)
                maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
                setTextIsSelectable(true)
                setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            }
            fila.addView(bubble)
        }

        if (rol == "m" && !streaming) {
            val actions = LinearLayout(this).apply { gravity = Gravity.START }
            val copy = MaterialButton(this).apply {
                text = "Copiar"
                setAllCaps(false)
                minHeight = 34
                setOnClickListener { copiar(texto) }
            }
            actions.addView(copy)
            if (index == mensajes.lastIndex) {
                val regen = MaterialButton(this).apply {
                        text = "Regenerar"
                    setAllCaps(false)
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

    /** Entrada del selector unificado: modelo de texto o de imagen. */
    private sealed class Entrada {
        abstract val id: String
        data class Texto(val m: MotorIA.Modelo) : Entrada() { override val id get() = m.id }
        data class Imagen(val m: ModelosImagen.ModeloImagen) : Entrada() { override val id get() = m.id }
    }

    private fun entradas(): List<Entrada> =
        MotorIA.MODELOS.map { Entrada.Texto(it) } + ModelosImagen.MODELOS.map { Entrada.Imagen(it) }

    private fun estaDescargada(e: Entrada) = when (e) {
        is Entrada.Texto -> motor.modeloDescargado(e.m)
        is Entrada.Imagen -> imagenes.descargado(e.m)
    }

    private fun estaActiva(e: Entrada) = when (e) {
        is Entrada.Texto -> modeloActual?.id == e.m.id
        is Entrada.Imagen -> modeloImagenActual?.id == e.m.id
    }

    private fun etiqueta(e: Entrada): String = when (e) {
        is Entrada.Texto -> {
            val estado = when {
                estaActiva(e) -> "✓ activo"
                estaDescargada(e) -> "✓ descargado"
                else -> "${e.m.tamanoMb} MB · descarga manual"
            }
            "${e.m.nombre}${if (e.m.vision) " · visión" else ""} · texto\n$estado"
        }
        is Entrada.Imagen -> {
            val estado = when {
                estaActiva(e) -> "✓ activo"
                estaDescargada(e) -> "✓ descargado"
                else -> "~${e.m.tamanoGb} GB · descarga manual"
            }
            "${e.m.nombre} · imagen ${e.m.resolucion}\n$estado"
        }
    }

    /** Un solo selector para todos los modelos. El botón principal cambia entre «Usar» y «Descargar». */
    private fun elegirModelo(preseleccion: String? = null) {
        if (generando) return
        val lista = entradas()
        val items = lista.map { etiqueta(it) }.toTypedArray()
        var elegido = lista.indexOfFirst { it.id == preseleccion }
            .takeIf { it >= 0 }
            ?: lista.indexOfFirst { estaActiva(it) }.coerceAtLeast(0)

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Modelos")
            .setSingleChoiceItems(items, elegido) { d, which ->
                elegido = which
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).text =
                    if (estaDescargada(lista[which])) "Usar" else "Descargar"
            }
            .setPositiveButton("Usar", null) // se sobrescribe abajo para poder decidir
            .setNegativeButton("Cancelar", null)
            .create()

        dialogo.setOnShowListener {
            val positivo = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            positivo.text = if (estaDescargada(lista[elegido])) "Usar" else "Descargar"
            positivo.setOnClickListener {
                val e = lista[elegido]
                dialogo.dismiss()
                if (estaDescargada(e)) usar(e) else confirmarDescarga(e)
            }
        }
        dialogo.show()
    }

    private fun usar(e: Entrada) {
        when (e) {
            is Entrada.Texto -> {
                getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("modelo", e.m.id).apply()
                preparar(e.m)
            }
            is Entrada.Imagen -> activarModeloImagen(e.m)
        }
    }

    private fun confirmarDescarga(e: Entrada) {
        val (nombre, tamano, libre) = when (e) {
            is Entrada.Texto -> Triple(e.m.nombre, "${e.m.tamanoMb} MB", "${motor.espacioLibreMb()} MB")
            is Entrada.Imagen -> Triple(e.m.nombre, "${e.m.tamanoGb} GB", "${imagenes.espacioLibreBytes() / (1024 * 1024)} MB")
        }
        AlertDialog.Builder(this)
            .setTitle("Descargar modelo")
            .setMessage("$nombre ocupa aproximadamente $tamano.\n\nEspacio libre: $libre.\n\nLa descarga solo empieza si tú la confirmas y puede reanudarse.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Descargar") { _, _ ->
                lifecycleScope.launch {
                    try {
                        progreso.visibility = View.VISIBLE
                        progreso.isIndeterminate = false
                        tvEstado.text = "Descargando $nombre…"
                        when (e) {
                            is Entrada.Texto -> motor.descargarModelo(e.m) { p ->
                                progreso.progress = p
                                tvEstado.text = "Descargando $nombre · $p%"
                            }
                            is Entrada.Imagen -> imagenes.descargar(e.m) { p, archivo ->
                                progreso.progress = p
                                tvEstado.text = "Descargando $nombre · $p% · $archivo"
                            }
                        }
                        progreso.visibility = View.GONE
                        tvEstado.text = "$nombre descargado. Pulsa «Usar» para activarlo."
                        Toast.makeText(this@ChatActivity, "$nombre descargado", Toast.LENGTH_SHORT).show()
                        // Reabrimos el selector con este modelo ya marcado y el botón en «Usar».
                        elegirModelo(e.id)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        progreso.visibility = View.GONE
                        tvEstado.text = "Error de descarga"
                        Toast.makeText(this@ChatActivity, ex.message ?: "No se pudo descargar", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun activarModeloImagen(m: ModelosImagen.ModeloImagen) {
        modeloImagenActual = m
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("modelo_imagen", m.id).apply()
        modeloActual = null
        motor.liberar()
        fluxGenerator?.close()
        fluxGenerator = null
        btnModelo.text = m.nombre
        btnImagen.visibility = View.GONE
        progreso.visibility = View.GONE
        tvEstado.text = "Imagen local · ${m.nombre} · lista"
        actualizarBotonEnvio()
    }

    private fun preparar(m: MotorIA.Modelo) {
        modeloActual = m
        modeloImagenActual = null
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().remove("modelo_imagen").apply()
        fluxGenerator?.close()
        fluxGenerator = null
        btnModelo.text = m.nombre
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
                actualizarBotonEnvio()
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
        if ((texto.isEmpty() && ruta == null) || generando) return false

        if (modeloImagenActual != null) {
            if (ruta != null) {
                Toast.makeText(this, "Este modo genera texto→imagen. Quita el adjunto para continuar.", Toast.LENGTH_LONG).show()
                return false
            }
            if (!imagenes.descargado(modeloImagenActual!!)) {
                Toast.makeText(this, "El modelo de imagen ya no está disponible. Descárgalo desde el selector de modelos.", Toast.LENGTH_LONG).show()
                return false
            }
            val prompt = if (texto.isEmpty()) "Una imagen fotográfica detallada" else texto
            etMensaje.setText("")
            mensajes.add("u" to prompt)
            render(null)
            generarImagenDesde(prompt)
            return true
        }

        if (modeloActual == null) return false
        val prompt = if (texto.isEmpty()) "Describe esta imagen." else texto
        etMensaje.setText("")
        quitarImagen()
        mensajes.add("u" to (if (ruta != null) "📷 $prompt" else prompt))
        render(null)
        generarDesde(prompt, ruta, guardar = true)
        return true
    }

    private fun actualizarBotonEnvio() {
        btnEnviar.isEnabled = when {
            generando -> false
            modeloImagenActual != null -> imagenes.descargado(modeloImagenActual!!)
            else -> modeloActual != null
        }
    }

    private fun generarImagenDesde(prompt: String) {
        generando = true
        actualizarBotonEnvio()
        tvEstado.text = "Generando imagen local…"
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    val generator = fluxGenerator ?: Flux2KleinGenerator(this@ChatActivity).also { fluxGenerator = it }
                    generator.generate(prompt) { estado ->
                        runOnUiThread { tvEstado.text = "Imagen · $estado" }
                    }
                }
                val file = File(filesDir, "generadas").apply { mkdirs() }
                    .let { File(it, "flux_${System.currentTimeMillis()}.png") }
                withContext(Dispatchers.IO) {
                    java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                mensajes.add("m" to "[[IMAGE]]${file.absolutePath}")
                guardarSesion()
                render(null)
                tvEstado.text = "Imagen generada · ${modeloImagenActual?.nombre ?: "FLUX"}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                mensajes.add("m" to "No se pudo generar la imagen: $reason")
                guardarSesion()
                render(null)
                tvEstado.text = "No se pudo generar la imagen"
                Toast.makeText(this@ChatActivity, reason, Toast.LENGTH_LONG).show()
            } finally {
                generando = false
                actualizarBotonEnvio()
            }
        }
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
        fluxGenerator?.close()
        motor.liberar()
        super.onDestroy()
    }
}
