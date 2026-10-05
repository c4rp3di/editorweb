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
import com.ejemplo.chat.ia.DebugLog
import com.ejemplo.chat.ia.MotorIA
import com.ejemplo.chat.ia.ModelosImagen
import com.ejemplo.chat.ia.llama.LlamaCppModel
import com.ejemplo.chat.ia.llama.LlamaCppModelManager
import com.ejemplo.chat.ia.llama.LlamaCppNative
import com.ejemplo.chat.ia.llama.NativeLlamaCppTextEngine
import android.content.Intent
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private lateinit var llamaManager: LlamaCppModelManager
    private val llamaEngine = NativeLlamaCppTextEngine()

    private val mensajes = mutableListOf<Pair<String, String>>()
    private var imagenRuta: String? = null
    private var generando = false
    private var modeloActual: MotorIA.Modelo? = null
    private var modeloLlamaActual: LlamaCppModel? = null
    private var modeloImagenActual: ModelosImagen.ModeloImagen? = null
    private var sesionId = "actual"

    // Caja de progreso que se muestra bajo el prompt mientras se genera una imagen.
    private var imagenEnCurso = false
    private var inicioImagen = 0L
    private var ultimoEstado = ""
    private var ultimaFrac = 0f
    private var cajaTexto: TextView? = null
    private var cajaBarra: ProgressBar? = null
    private var ticker: Job? = null
    private var tituloSesion = "Nueva conversación"

    private val selectorImagen = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) adjuntar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        DebugLog.init(this)
        motor = MotorIA(this)
        imagenes = ModelosImagen(this)
        llamaManager = LlamaCppModelManager(this)

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
        val btnMotor = findViewById<MaterialButton>(R.id.btnMotorImagen)
        btnMotor.text = "🧪  Motor de imagen: pendiente de verificación"
        btnMotor.setOnClickListener {
            Toast.makeText(this, "El backend FLUX experimental se ha retirado. La nueva base será stable-diffusion.cpp + Vulkan, pendiente de validación en este dispositivo.", Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialButton>(R.id.btnArchivos).setOnClickListener {
            startActivity(Intent(this, CreatedFilesActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnDepuracion).setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            mostrarDepuracion()
        }
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
        if (DebugLog.interruptedLast) {
            Toast.makeText(
                this,
                "La última generación de imagen se interrumpió (la app se cerró). Mira 🐞 Depuración en el menú.",
                Toast.LENGTH_LONG
            ).show()
        }

        val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
        val guardado = MotorIA.MODELOS.firstOrNull { it.id == prefs.getString("modelo", null) }
        val guardadoLlama = prefs.getString("modelo_llama", null)
        val imagenGuardada = modeloImagenActual
        if (imagenGuardada != null) {
            activarModeloImagen(imagenGuardada)
        } else if (guardadoLlama == LlamaCppModel.DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M.id && llamaManager.modeloListo(LlamaCppModel.DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M)) prepararLlama(LlamaCppModel.DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M) else if (guardado != null && motor.modeloDescargado(guardado)) preparar(guardado) else {
            tvEstado.text = "Elige un modelo para descargar y usarlo"
            elegirModelo()
        }
    }

    /** Historial para el modelo de texto: sin respuestas de imagen ni los prompts que las originaron. */
    private fun historialTexto(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (msg in mensajes) {
            if (msg.first == "m" && msg.second.startsWith("[[IMAGE]]")) {
                if (out.isNotEmpty() && out.last().first == "u") out.removeAt(out.lastIndex)
            } else out.add(msg)
        }
        return out
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
        tituloSesion = tituloGuardado(id) ?: mensajes.firstOrNull { it.first == "u" }?.second
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

    /** Título personalizado guardado en el archivo paralelo <id>.title (null si no existe). */
    private fun tituloGuardado(id: String): String? =
        File(carpetaChats(), "$id.title").takeIf { it.exists() }
            ?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun renderSidebar() {
        listaConversaciones.removeAllViews()
        val files = carpetaChats().listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val fmt = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
        val colorTexto = resolveTextColor()
        files.forEach { file ->
            val id = file.nameWithoutExtension
            val (title, count) = try {
                val arr = JSONArray(file.readText())
                val t = if (arr.length() > 0) arr.getJSONObject(0).getString("t").replace(Regex("\\s+"), " ").take(34)
                        else "Nueva conversación"
                t to arr.length()
            } catch (_: Exception) { "Nueva conversación" to 0 }
            val nombre = tituloGuardado(id) ?: title

            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_model)
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    if (generando) return@setOnClickListener
                    cargarSesion(id)
                    render(null)
                    drawer.closeDrawer(GravityCompat.START)
                    if (modeloActual != null) motor.nuevaConversacion(historialTexto())
                }
                setOnLongClickListener {
                    if (generando) return@setOnLongClickListener true
                    renombrarChat(id, nombre)
                    true
                }
            }
            val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textos.addView(TextView(this).apply {
                text = (if (id == sesionId) "\u25CF  " else "") + nombre
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(colorTexto)
                setTypeface(typeface, if (id == sesionId) Typeface.BOLD else Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-1, -2))
            textos.addView(TextView(this).apply {
                text = "$count mensajes · ${fmt.format(Date(file.lastModified()))}"
                textSize = 11f
                setTextColor(Color.GRAY)
                maxLines = 1
            }, LinearLayout.LayoutParams(-1, -2))
            fila.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            fila.addView(TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { confirmarBorrarChat(id, nombre) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            listaConversaciones.addView(fila, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 2, 0, 2) })
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

    /** Pulsación larga sobre un chat del historial: permite ponerle un nombre personalizado. */
    private fun renombrarChat(id: String, nombreActual: String) {
        val input = EditText(this).apply {
            setText(nombreActual)
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Renombrar conversación")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(6), dp(20), 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            })
            .setPositiveButton("Guardar") { _, _ ->
                val nuevo = input.text.toString().trim()
                val f = File(carpetaChats(), "$id.title")
                if (nuevo.isEmpty()) f.delete() else f.writeText(nuevo.take(60))
                if (id == sesionId) tituloSesion = nuevo.ifEmpty { "Nueva conversación" }
                DebugLog.log("UI", "Conversación renombrada: $id -> ${nuevo.take(40)}")
                renderSidebar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** La ✕ de cada fila: pide confirmación y, si borra la sesión activa, abre una nueva vacía. */
    private fun confirmarBorrarChat(id: String, nombre: String) {
        AlertDialog.Builder(this)
            .setTitle("Borrar conversación")
            .setMessage("¿Borrar «$nombre»? Esta acción no se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                archivoSesion(id).delete()
                File(carpetaChats(), "$id.title").delete()
                DebugLog.log("UI", "Conversación borrada: $id")
                if (id == sesionId) {
                    mensajes.clear()
                    sesionId = UUID.randomUUID().toString()
                    tituloSesion = "Nueva conversación"
                    getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("sesion", sesionId).apply()
                    motor.nuevaConversacion()
                    render(null)
                    tvEstado.text = "Nueva conversación"
                }
                renderSidebar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun textoCaja(): String {
        val s = (System.currentTimeMillis() - inicioImagen) / 1000
        return "$ultimoEstado\n⏱ ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun mostrarDepuracion() {
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setTextColor(resolveTextColor())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        fun refrescar() {
            tv.text = DebugLog.resumen() + "\n\n" + DebugLog.read().ifBlank { "(sin registros)" }
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
        fun boton(texto: String, accion: () -> Unit) = MaterialButton(this).apply {
            text = texto
            setAllCaps(false)
            textSize = 12f
            setOnClickListener { accion() }
        }
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(boton("Actualizar") { refrescar() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(boton("Copiar") {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("depuracion", tv.text))
                Toast.makeText(this@ChatActivity, "Registro copiado", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(boton("Borrar") { DebugLog.clear(); refrescar() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fila)
            addView(sv, LinearLayout.LayoutParams(-1, dp(420)))
        }
        refrescar()
        AlertDialog.Builder(this)
            .setTitle("Depuración")
            .setView(raiz)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLog.log("MEM", "onTrimMemory nivel=$level · ${DebugLog.mem()}")
    }

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
            val esImagen = texto.startsWith("[[IMAGE]]")
            val actions = LinearLayout(this).apply { gravity = Gravity.START }
            if (!esImagen) {
                val copy = MaterialButton(this).apply {
                    text = "Copiar"
                    setAllCaps(false)
                    minHeight = 34
                    setOnClickListener { copiar(texto) }
                }
                actions.addView(copy)
            }
            val puedeRegenerar = index == mensajes.lastIndex &&
                (if (esImagen) modeloImagenActual != null else modeloActual != null)
            if (puedeRegenerar) {
                val regen = MaterialButton(this).apply {
                    text = "Regenerar"
                    setAllCaps(false)
                    minHeight = 34
                    setOnClickListener { regenerarUltima() }
                }
                actions.addView(regen)
            }
            if (actions.childCount > 0) fila.addView(actions)
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
        if (generando || mensajes.size < 2) return
        val ultimoUsuario = mensajes.indexOfLast { it.first == "u" }
        if (ultimoUsuario < 0) return
        val ultima = mensajes.last()
        if (modeloActual == null && modeloLlamaActual == null) return
        while (mensajes.size > ultimoUsuario + 1) mensajes.removeAt(mensajes.lastIndex)
        val prompt = mensajes[ultimoUsuario].second.removePrefix("📷 ").trim()
        generarDesde(prompt, null, guardar = true)
    }

    /** Entrada del selector unificado: modelo de texto o de imagen. */
    private sealed class Entrada {
        abstract val id: String
        data class Texto(val m: MotorIA.Modelo) : Entrada() { override val id get() = m.id }
        data class Llama(val m: LlamaCppModel) : Entrada() { override val id get() = m.id }
        data class Imagen(val m: ModelosImagen.ModeloImagen) : Entrada() { override val id get() = m.id }
    }

    private fun entradas(): List<Entrada> =
        MotorIA.MODELOS.map { Entrada.Texto(it) } + listOf(Entrada.Llama(LlamaCppModel.DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M)) + ModelosImagen.MODELOS.map { Entrada.Imagen(it) }

    private fun estaDescargada(e: Entrada) = when (e) {
        is Entrada.Texto -> motor.modeloDescargado(e.m)
        is Entrada.Llama -> llamaManager.modeloListo(e.m)
        is Entrada.Imagen -> imagenes.descargado(e.m)
    }

    private fun estaActiva(e: Entrada) = when (e) {
        is Entrada.Texto -> modeloActual?.id == e.m.id
        is Entrada.Llama -> modeloLlamaActual?.id == e.m.id
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
        is Entrada.Llama -> {
            val estado = when {
                estaActiva(e) -> "✓ seleccionado"
                estaDescargada(e) -> "✓ GGUF verificado"
                else -> "${e.m.tamanoAproximadoMb} MB · descarga manual"
            }
            "${e.m.nombre} · texto\n$estado"
        }
        is Entrada.Imagen -> "${e.m.nombre} · imagen\n🧪 backend pendiente de verificación · ${e.m.backend}"
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

    private fun confirmarDescarga(e: Entrada) {
        when (e) {
            is Entrada.Llama -> {
                AlertDialog.Builder(this)
                    .setTitle("Descargar ${e.m.nombre}")
                    .setMessage("Se descargará manualmente el GGUF (${e.m.tamanoAproximadoMb} MB). Solo se activará después de comprobar la cabecera GGUF y el SHA-256. ¿Continuar?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Descargar") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                progreso.visibility = View.VISIBLE
                                progreso.isIndeterminate = false
                                progreso.progress = 0
                                tvEstado.text = "Descargando ${e.m.nombre}…"
                                llamaManager.descargarModelo(e.m) { p ->
                                    if (p.porcentaje >= 0) progreso.progress = p.porcentaje
                                }
                                progreso.visibility = View.GONE
                                tvEstado.text = "GGUF verificado · ${e.m.nombre}"
                                Toast.makeText(this@ChatActivity, "GGUF descargado y verificado", Toast.LENGTH_SHORT).show()
                                elegirModelo(e.m.id)
                            } catch (ce: CancellationException) {
                                progreso.visibility = View.GONE
                                throw ce
                            } catch (ex: Exception) {
                                progreso.visibility = View.GONE
                                tvEstado.text = "Error verificando el GGUF"
                                Toast.makeText(this@ChatActivity, ex.message ?: "No se pudo descargar/verificar el GGUF", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .show()
            }
            is Entrada.Imagen -> {
                Toast.makeText(
                    this,
                    "Este modelo de imagen todavía no está disponible para descargar: el backend está pendiente de verificación.",
                    Toast.LENGTH_LONG
                ).show()
            }
            is Entrada.Texto -> {
                AlertDialog.Builder(this)
                    .setTitle("Descargar ${e.m.nombre}")
                    .setMessage("Se descargará manualmente el modelo (${e.m.tamanoMb} MB) y quedará guardado solo en el dispositivo. ¿Continuar?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Descargar") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                progreso.visibility = View.VISIBLE
                                progreso.isIndeterminate = false
                                progreso.progress = 0
                                tvEstado.text = "Descargando ${e.m.nombre}…"
                                motor.descargarModelo(e.m) { p ->
                                    progreso.progress = p
                                }
                                progreso.visibility = View.GONE
                                tvEstado.text = "Descarga verificada localmente · ${e.m.nombre}"
                                Toast.makeText(this@ChatActivity, "Descarga completada", Toast.LENGTH_SHORT).show()
                                elegirModelo(e.m.id)
                            } catch (ce: CancellationException) {
                                progreso.visibility = View.GONE
                                throw ce
                            } catch (ex: Exception) {
                                progreso.visibility = View.GONE
                                tvEstado.text = "Error al descargar el modelo"
                                Toast.makeText(this@ChatActivity, ex.message ?: "No se pudo descargar el modelo", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun usar(e: Entrada) {
        when (e) {
            is Entrada.Texto -> {
                getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().remove("modelo_llama").putString("modelo", e.m.id).apply()
                preparar(e.m)
            }
            is Entrada.Llama -> {
                getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().remove("modelo").putString("modelo_llama", e.m.id).apply()
                prepararLlama(e.m)
            }
            is Entrada.Imagen -> Toast.makeText(this, "Este backend de imagen está pendiente de verificación y todavía no se puede usar.", Toast.LENGTH_LONG).show()
        }
    }

    private fun activarModeloImagen(m: ModelosImagen.ModeloImagen) {
        modeloImagenActual = m
        modeloActual = null
        modeloLlamaActual = null
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().remove("modelo_llama").apply()
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("modelo_imagen", m.id).apply()
        btnModelo.text = m.nombre
        btnImagen.visibility = View.GONE
        tvEstado.text = "Imagen · backend pendiente de verificación"
        actualizarBotonEnvio()
    }

    private fun prepararLlama(m: LlamaCppModel) {
        modeloLlamaActual = m
        modeloActual = null
        modeloImagenActual = null
        btnModelo.text = m.nombre
        btnImagen.visibility = View.GONE
        btnEnviar.isEnabled = false
        if (!llamaManager.modeloListo(m)) {
            tvEstado.text = "GGUF no descargado"
            Toast.makeText(this, "Descarga primero este modelo desde el selector", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val verificacion = llamaManager.verificarModelo(m)
            if (!verificacion.valido) {
                tvEstado.text = "GGUF no válido"
                Toast.makeText(this@ChatActivity, verificacion.mensaje, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!LlamaCppNative.estaDisponible()) {
                tvEstado.text = "GGUF verificado · runtime nativo no disponible"
                Toast.makeText(this@ChatActivity, "La biblioteca nativa llama.cpp no se pudo cargar.", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                motor.liberar()
                progreso.visibility = View.VISIBLE
                progreso.isIndeterminate = true
                tvEstado.text = "Cargando ${m.nombre} · llama.cpp…"
                llamaEngine.cargar(llamaManager.archivoDe(m), contexto = 4096, hilos = 4)
                progreso.visibility = View.GONE
                tvEstado.text = "Listo · ${m.nombre} · llama.cpp CPU"
                actualizarBotonEnvio()
            } catch (e: Exception) {
                progreso.visibility = View.GONE
                tvEstado.text = "No se pudo cargar ${m.nombre}"
                Toast.makeText(this@ChatActivity, e.message ?: "Error al cargar llama.cpp", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun preparar(m: MotorIA.Modelo) {
        llamaEngine.liberar()
        modeloActual = m
        modeloImagenActual = null
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().remove("modelo_imagen").apply()
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
                motor.inicializar(m, historialTexto())
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
            Toast.makeText(this, "La generación de imagen estará disponible cuando terminemos la validación de stable-diffusion.cpp + Vulkan.", Toast.LENGTH_LONG).show()
            return false
        }

        if (modeloActual == null && modeloLlamaActual == null) return false
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
            else -> modeloActual != null || modeloLlamaActual != null
        }
    }

    private fun generarDesde(prompt: String, ruta: String?, guardar: Boolean) {
        generando = true
        btnEnviar.isEnabled = false
        tvEstado.text = "Generando · ${modeloLlamaActual?.nombre ?: modeloActual?.nombre ?: ""}"
        lifecycleScope.launch {
            val respuesta = StringBuilder()
            try {
                if (modeloLlamaActual != null) {
                    llamaEngine.generar(prompt, maxTokens = 256).collect { trozo ->
                        respuesta.append(trozo)
                        render(respuesta.toString())
                    }
                } else {
                    motor.generar(prompt, ruta).collect { trozo ->
                        respuesta.append(trozo)
                        render(respuesta.toString())
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { respuesta.append("Error: ${e.message ?: e.javaClass.simpleName}") }
            mensajes.add("m" to respuesta.toString())
            if (guardar) guardarSesion()
            render(null)
            tvEstado.text = "Listo · ${modeloLlamaActual?.nombre ?: modeloActual?.nombre ?: ""}"
            generando = false
            btnEnviar.isEnabled = true
        }
    }

    override fun onDestroy() {
        llamaEngine.liberar()
        motor.liberar()
        super.onDestroy()
    }
}
