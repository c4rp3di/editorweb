package com.ejemplo.chat.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
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
import com.ejemplo.chat.ia.llama.LlamaCppModel
import com.ejemplo.chat.ia.llama.LlamaCppModelManager
import com.ejemplo.chat.ia.llama.LlamaCppNative
import com.ejemplo.chat.ia.llama.NativeLlamaCppTextEngine
import android.content.Intent
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    private lateinit var llamaManager: LlamaCppModelManager
    private val llamaEngine = NativeLlamaCppTextEngine()

    private val mensajes = mutableListOf<Pair<String, String>>()
    /** Adjunto pendiente de enviar: archivo de texto (bloque) y/o imagen (ruta + OCR). */
    private class Adjunto(val nombre: String, val detalle: String, val bloque: String?, val imagenRuta: String?)
    private val adjuntos = mutableListOf<Adjunto>()
    private var generando = false
    private var modeloActual: MotorIA.Modelo? = null
    private var modeloLlamaActual: LlamaCppModel? = null
    private var sesionId = "actual"

    private var tituloSesion = "Nueva conversación"

    private val selectorAdjunto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) adjuntar(uri)
    }

    /** Reconocedor de texto en el dispositivo (ML Kit, alfabeto latino: español/inglés...). Se crea al usarlo. */
    private val reconocedor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        DebugLog.init(this)
        LlamaCppNative.estaDisponible() // activa pronto el registro nativo (stdout/stderr + crashes)
        motor = MotorIA(this)
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
        findViewById<MaterialButton>(R.id.btnDepuracion).setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            mostrarDepuracion()
        }
        btnEnviar.setOnClickListener { if (generando) detenerGeneracion() else enviar() }
        btnImagen.setOnClickListener { selectorAdjunto.launch(arrayOf("*/*")) }
        btnModelo.setOnClickListener { elegirModelo() }
        tvAdjunto.setOnClickListener { quitarAdjuntos() }
        etMensaje.setOnEditorActionListener { _, actionId, _ -> if (actionId != 0) enviar() else false }
        btnEnviar.isEnabled = false

        actualizarBotonEnvio()

        cargarSesionActual()
        render(null)
        renderSidebar()
        if (DebugLog.interruptedLast) {
            Toast.makeText(
                this,
                "La última generación se interrumpió (la app se cerró). Mira 🐞 Depuración en el menú.",
                Toast.LENGTH_LONG
            ).show()
        }

        val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
        val guardado = MotorIA.MODELOS.firstOrNull { it.id == prefs.getString("modelo", null) }
        val guardadoLlama = prefs.getString("modelo_llama", null)
        if (guardadoLlama == LlamaCppModel.DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M.id && llamaManager.modeloListo(LlamaCppModel.DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M)) prepararLlama(LlamaCppModel.DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M) else if (guardado != null && motor.modeloDescargado(guardado)) preparar(guardado) else {
            tvEstado.text = "Elige un modelo para descargar y usarlo"
            elegirModelo()
        }
    }

    /**
     * Historial para el modelo. Los adjuntos (archivos / OCR) viajan dentro del mensaje del usuario entre marcas
     * [[ADJ:nombre]]…[[/ADJ]]; aquí se convierten a texto para el modelo. Solo el último mensaje con adjuntos conserva
     * su contenido: los anteriores se sustituyen por una nota para no llenar el contexto.
     */
    private fun historialTexto(): List<Pair<String, String>> {
        val ultimoConAdjuntos = mensajes.indexOfLast { it.first == "u" && it.second.contains("[[ADJ:") }
        return mensajes.mapIndexed { i, msg ->
            if (msg.first != "u") msg
            else if (i == ultimoConAdjuntos || !msg.second.contains("[[ADJ:")) msg.first to paraModelo(msg.second)
            else msg.first to paraModelo(msg.second, omitirContenido = true)
        }
    }

    private val regexAdjunto = Regex("\\[\\[ADJ:(.*?)\\]\\]\\n(.*?)\\n\\[\\[/ADJ\\]\\]\\n?", RegexOption.DOT_MATCHES_ALL)
    private val regexImagen = Regex("\\[\\[IMG:(.*?)\\]\\]\\n?")

    /** Texto que ve el modelo: cada adjunto como bloque de código con su nombre. */
    private fun paraModelo(guardado: String, omitirContenido: Boolean = false): String =
        guardado.replace(regexAdjunto) { m ->
            if (omitirContenido) "[Adjunto «${m.groupValues[1]}» omitido para ahorrar contexto]\n"
            else "Adjunto «${m.groupValues[1]}»:\n```\n${m.groupValues[2]}\n```\n\n"
        }.replace(regexImagen, "").trim()

    /** Texto que ve el usuario en la burbuja: chips compactos en vez del contenido de los adjuntos. */
    private fun visibleDeUsuario(guardado: String): String =
        guardado.replace(regexAdjunto) { m ->
            val lineas = m.groupValues[2].count { it == '\n' } + 1
            "📎 ${m.groupValues[1]} ($lineas líneas)\n"
        }.replace(regexImagen, "🖼 imagen adjunta\n").trim()

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
            ?.let { visibleDeUsuario(it) }?.replace(Regex("\\s+"), " ")?.take(34) ?: "Nueva conversación"
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
                val t = if (arr.length() > 0) visibleDeUsuario(arr.getJSONObject(0).getString("t")).replace(Regex("\\s+"), " ").take(34)
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
                    else if (modeloLlamaActual != null) lifecycleScope.launch { restaurarContextoLlama() }
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
        run {
            val bubble = TextView(this).apply {
                text = markdownBasico(if (rol == "m") visibleDeRespuesta(texto, !streaming) else visibleDeUsuario(texto))
                textSize = 15.5f
                setTextColor(if (rol == "u") Color.WHITE else resolveTextColor())
                setPadding(16, 12, 16, 12)
                setBackgroundResource(if (rol == "u") R.drawable.bg_user else R.drawable.bg_model)
                maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
                setTextIsSelectable(true)
                setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            }
            fila.addView(bubble)
            if (streaming) burbujaStreaming = bubble
        }

        if (rol == "m" && !streaming) {
            val actions = LinearLayout(this).apply { gravity = Gravity.START }
            val copy = MaterialButton(this).apply {
                text = "Copiar"
                setAllCaps(false)
                minHeight = 34
                setOnClickListener { copiar(visibleDeRespuesta(texto, true)) }
            }
            actions.addView(copy)
            val puedeRegenerar = index == mensajes.lastIndex && (modeloActual != null || modeloLlamaActual != null)
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
        if (modeloActual == null && modeloLlamaActual == null) return
        while (mensajes.size > ultimoUsuario + 1) mensajes.removeAt(mensajes.lastIndex)
        val guardado = mensajes[ultimoUsuario].second
        if (modeloLlamaActual != null) {
            lifecycleScope.launch {
                restaurarContextoLlama(excluirUltimo = true)   // el último mensaje (la pregunta) se vuelve a enviar como turno nuevo
                generarDesde(guardado, guardar = true)
            }
        } else {
            // LiteRT: la conversación del motor ya contiene el turno anterior; se reconstruye hasta el mensaje previo.
            motor.nuevaConversacion(historialTexto().dropLast(1))
            generarDesde(guardado, guardar = true)
        }
    }

    /** Entrada del selector: modelo LiteRT o modelo llama.cpp. */
    private sealed class Entrada {
        abstract val id: String
        data class Texto(val m: MotorIA.Modelo) : Entrada() { override val id get() = m.id }
        data class Llama(val m: LlamaCppModel) : Entrada() { override val id get() = m.id }
    }

    private fun entradas(): List<Entrada> =
        MotorIA.MODELOS.map { Entrada.Texto(it) } + listOf(Entrada.Llama(LlamaCppModel.DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M))

    private fun estaDescargada(e: Entrada) = when (e) {
        is Entrada.Texto -> motor.modeloDescargado(e.m)
        is Entrada.Llama -> llamaManager.modeloListo(e.m)
    }

    private fun estaActiva(e: Entrada) = when (e) {
        is Entrada.Texto -> modeloActual?.id == e.m.id
        is Entrada.Llama -> modeloLlamaActual?.id == e.m.id
    }

    private fun tituloDe(e: Entrada): String = when (e) {
        is Entrada.Texto -> e.m.nombre + if (e.m.vision) " · visión" else ""
        is Entrada.Llama -> e.m.nombre
    }

    private fun detalleDe(e: Entrada): String = when (e) {
        is Entrada.Texto -> when {
            estaActiva(e) -> "✓ activo"
            estaDescargada(e) -> "✓ descargado"
            else -> "${e.m.tamanoMb} MB · descarga manual"
        }
        is Entrada.Llama -> when {
            estaActiva(e) -> "✓ seleccionado"
            estaDescargada(e) -> "✓ GGUF verificado"
            else -> "${e.m.tamanoAproximadoMb} MB · descarga manual"
        }
    }

    private fun colorAtributo(attr: Int, defecto: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        return try { ta.getColor(0, defecto) } finally { ta.recycle() }
    }

    /** Selector de modelos en hoja inferior. */
    private fun elegirModelo(preseleccion: String? = null) {
        if (generando) return
        val lista = entradas()
        val hoja = BottomSheetDialog(this)
        val colorPrincipal = colorAtributo(android.R.attr.textColorPrimary, Color.WHITE)
        val colorSecundario = colorAtributo(android.R.attr.textColorSecondary, Color.GRAY)
        val acento = Color.parseColor("#0F766E")

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        raiz.addView(TextView(this).apply {
            text = "Modelos"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrincipal)
            setPadding(dp(4), 0, 0, dp(4))
        })

        val secciones = listOf(
            "Modelos con visión (ven imágenes + OCR)" to lista.filter { it is Entrada.Texto && it.m.vision },
            "Modelos de texto (imágenes solo por OCR)" to lista.filter { (it is Entrada.Texto && !it.m.vision) || it is Entrada.Llama }
        )
        for ((nombreSeccion, items) in secciones) {
            if (items.isEmpty()) continue
            raiz.addView(TextView(this).apply {
                text = nombreSeccion.uppercase(Locale.getDefault())
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(colorSecundario)
                setPadding(dp(4), dp(14), 0, dp(6))
            })
            for (e in items) {
                val activa = estaActiva(e)
                val sugerida = !activa && e.id == preseleccion
                val fila = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.parseColor(if (activa || sugerida) "#220F766E" else "#14808080"))
                        if (activa || sugerida) setStroke(dp(1), acento)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        hoja.dismiss()
                        if (estaDescargada(e)) usar(e) else confirmarDescarga(e)
                    }
                }
                val columna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                columna.addView(TextView(this).apply {
                    text = tituloDe(e)
                    textSize = 16f
                    setTextColor(colorPrincipal)
                })
                columna.addView(TextView(this).apply {
                    text = if (sugerida) "Descargado · toca para usar" else detalleDe(e)
                    textSize = 13f
                    setTextColor(colorSecundario)
                    setPadding(0, dp(2), 0, 0)
                })
                fila.addView(columna, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (activa) {
                    fila.addView(TextView(this).apply {
                        text = "✓"
                        textSize = 18f
                        setTextColor(acento)
                    })
                }
                raiz.addView(fila, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }

        val sv = ScrollView(this)
        sv.addView(raiz)
        hoja.setContentView(sv)
        hoja.setOnShowListener { hoja.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        hoja.show()
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
        }
    }

    private suspend fun restaurarContextoLlama(excluirUltimo: Boolean = false) {
        if (modeloLlamaActual == null) return
        val historial = historialTexto().let { if (excluirUltimo) it.dropLast(1) else it }
        try {
            val ok = llamaEngine.restaurarHistorial(historial)
            if (!ok) DebugLog.log("LLAMA", "⚠ no se pudo restaurar el contexto de la sesión actual · mensajes=${historial.size}")
        } catch (e: Exception) {
            DebugLog.log("LLAMA", "⚠ excepción restaurando contexto: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun prepararLlama(m: LlamaCppModel) {
        modeloLlamaActual = m
        modeloActual = null
        btnModelo.text = m.nombre
        btnEnviar.isEnabled = false
        if (!llamaManager.modeloListo(m)) {
            tvEstado.text = "GGUF no descargado"
            Toast.makeText(this, "Descarga primero este modelo desde el selector", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            DebugLog.log("LLAMA", "preparando ${m.nombre} · archivo=${llamaManager.archivoDe(m).name}")
            val verificacion = llamaManager.verificarModelo(m)
            DebugLog.log("LLAMA", "verificación GGUF: válido=${verificacion.valido} · ${verificacion.mensaje}")
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
                llamaEngine.cargar(llamaManager.archivoDe(m), contexto = 8192, hilos = 4)
                restaurarContextoLlama()
                progreso.visibility = View.GONE
                tvEstado.text = "Listo · ${m.nombre} · llama.cpp CPU"
                actualizarBotonEnvio()
            } catch (e: Exception) {
                DebugLog.log("LLAMA", "⚠ error al preparar ${m.nombre}: ${e.javaClass.simpleName}: ${e.message}")
                progreso.visibility = View.GONE
                tvEstado.text = "No se pudo cargar ${m.nombre}"
                Toast.makeText(this@ChatActivity, e.message ?: "Error al cargar llama.cpp", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun preparar(m: MotorIA.Modelo) {
        llamaEngine.liberar()
        modeloActual = m
        modeloLlamaActual = null
        btnModelo.text = m.nombre
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

    // ───────────────────────── Adjuntos: archivos de texto e imágenes (OCR) ─────────────────────────

    /** Caracteres de adjuntos que caben en el contexto del modelo activo (el resto se recorta por el medio). */
    private fun limiteCaracteres(): Int = when {
        modeloLlamaActual != null -> 10_000
        modeloActual?.vision == true -> 10_000
        else -> 5_000
    }

    private fun nombreDe(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "archivo"
    } catch (_: Exception) { uri.lastPathSegment ?: "archivo" }

    private fun adjuntar(uri: Uri) {
        lifecycleScope.launch {
            val nombre = nombreDe(uri)
            val mime = contentResolver.getType(uri) ?: ""
            val esImagen = mime.startsWith("image/") ||
                Regex(".*\\.(jpe?g|png|webp|bmp|gif|heic)$", RegexOption.IGNORE_CASE).matches(nombre)
            try {
                tvEstado.text = "Leyendo $nombre…"
                if (esImagen) adjuntarImagen(uri, nombre) else adjuntarTexto(uri, nombre)
                mostrarAdjuntos()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.log("ADJ", "⚠ no se pudo adjuntar $nombre: ${e.javaClass.simpleName}: ${e.message}")
                Toast.makeText(this@ChatActivity, e.message ?: "No se pudo adjuntar $nombre", Toast.LENGTH_LONG).show()
                tvEstado.text = "No se pudo adjuntar $nombre"
            }
        }
    }

    private fun mostrarAdjuntos() {
        if (adjuntos.isEmpty()) { tvAdjunto.visibility = View.GONE; return }
        tvAdjunto.text = adjuntos.joinToString("\n") { "📎 ${it.nombre} · ${it.detalle}" } + "\n(toca para quitar todo)"
        tvAdjunto.visibility = View.VISIBLE
        tvEstado.text = "Adjuntos listos · escribe tu pregunta"
    }

    private fun quitarAdjuntos() { adjuntos.clear(); tvAdjunto.visibility = View.GONE }

    private suspend fun adjuntarTexto(uri: Uri, nombre: String) {
        val (texto, cortadoLectura) = withContext(Dispatchers.IO) { leerTexto(uri) }
        val usado = adjuntos.sumOf { it.bloque?.length ?: 0 }
        val libre = limiteCaracteres() - usado
        if (libre < 1_000) throw IllegalStateException("Ya hay demasiado contenido adjunto para este modelo. Quita algún adjunto (toca el aviso).")
        val (final, recortado) = ajustar(texto, libre)
        val limpio = final.replace("[[/ADJ]]", "[[ /ADJ]]")
        val lineas = limpio.count { it == '\n' } + 1
        val aviso = if (recortado || cortadoLectura) " · recortado" else ""
        adjuntos.add(Adjunto(nombre, "$lineas líneas$aviso", "[[ADJ:$nombre]]\n$limpio\n[[/ADJ]]", null))
        DebugLog.log("ADJ", "texto adjunto «$nombre» · ${texto.length} car → ${limpio.length}${if (recortado) " (recortado por el medio)" else ""} · límite ${limiteCaracteres()}")
    }

    /** Lee un archivo como texto (UTF-8, o Latin-1 si no es UTF-8 válido). Rechaza binarios. Máx. 3 MB. */
    private fun leerTexto(uri: Uri): Pair<String, Boolean> {
        val max = 3 * 1024 * 1024
        val salida = java.io.ByteArrayOutputStream()
        var cortado = false
        contentResolver.openInputStream(uri)?.use { entrada ->
            val tmp = ByteArray(8192)
            var total = 0
            while (true) {
                val n = entrada.read(tmp)
                if (n < 0) break
                if (total + n > max) { salida.write(tmp, 0, max - total); cortado = true; break }
                salida.write(tmp, 0, n)
                total += n
            }
        } ?: throw IllegalStateException("No se pudo abrir el archivo")
        val bytes = salida.toByteArray()
        if (bytes.isEmpty()) throw IllegalStateException("El archivo está vacío")
        if (bytes.take(4096).any { it == 0.toByte() }) throw IllegalStateException("Eso no parece un archivo de texto (es binario).")
        val texto = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            if (cortado) String(bytes, Charsets.UTF_8) else String(bytes, Charsets.ISO_8859_1)
        }
        return texto to cortado
    }

    /**
     * Prepara un texto (log, código, OCR) para el contexto: colapsa líneas idénticas consecutivas (los logs repiten mucho),
     * acorta líneas enormes y, si aún no cabe, conserva el principio (30 %) y el final (70 %), que es donde suelen estar los errores.
     * Devuelve el texto y si hubo que recortar por el medio.
     */
    private fun ajustar(original: String, limite: Int): Pair<String, Boolean> {
        val sb = StringBuilder()
        var anterior: String? = null
        var repes = 0
        fun volcar() {
            val a = anterior ?: return
            sb.append(a)
            if (repes > 1) sb.append("  (×$repes)")
            sb.append('\n')
        }
        for (linea in original.lineSequence()) {
            val l = (if (linea.length > 300) linea.take(300) + "…" else linea).trimEnd()
            if (l == anterior) repes++ else { volcar(); anterior = l; repes = 1 }
        }
        volcar()
        val t = sb.toString().trimEnd()
        if (t.length <= limite) return t to false
        val cabeza = (limite * 0.3).toInt()
        val cola = limite - cabeza
        val omitidas = t.substring(cabeza, t.length - cola).count { it == '\n' }
        return (t.take(cabeza) + "\n[… recortado: ~$omitidas líneas omitidas …]\n" + t.takeLast(cola)) to true
    }

    private suspend fun adjuntarImagen(uri: Uri, nombre: String) {
        val ruta = withContext(Dispatchers.IO) { reducirImagen(uri) } ?: throw IllegalStateException("No se pudo leer la imagen")
        tvEstado.text = "Reconociendo texto de $nombre (OCR)…"
        val textoOcr = try { ocr(ruta) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            DebugLog.log("ADJ", "⚠ OCR falló: ${e.javaClass.simpleName}: ${e.message}")
            ""
        }
        val vision = modeloActual?.vision == true
        if (textoOcr.isBlank() && !vision) {
            throw IllegalStateException("No se detectó texto en la imagen y este modelo no puede verla. Elige un modelo con visión (Gemma 4) para describirla.")
        }
        adjuntos.removeAll { it.imagenRuta != null }
        val usado = adjuntos.sumOf { it.bloque?.length ?: 0 }
        val libre = (limiteCaracteres() - usado).coerceAtLeast(1_500)
        val (final, recortado) = ajustar(textoOcr, libre)
        val bloque = if (final.isNotBlank()) "[[ADJ:$nombre · OCR]]\n${final.replace("[[/ADJ]]", "[[ /ADJ]]")}\n[[/ADJ]]" else null
        val lineas = if (final.isBlank()) 0 else final.count { it == '\n' } + 1
        val detalle = (if (vision) "la ve el modelo · " else "") + "OCR $lineas líneas" + if (recortado) " · recortado" else ""
        adjuntos.add(Adjunto(nombre, detalle, bloque, ruta))
        DebugLog.log("ADJ", "imagen adjunta «$nombre» · OCR ${textoOcr.length} car · visión=$vision · $ruta")
    }

    private suspend fun ocr(ruta: String): String = suspendCancellableCoroutine { cont ->
        val imagen = InputImage.fromFilePath(this, Uri.fromFile(File(ruta)))
        reconocedor.process(imagen)
            .addOnSuccessListener { r -> if (cont.isActive) cont.resume(r.text) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

    /** Copia la imagen (máx. 1280 px, con la rotación EXIF aplicada) a filesDir/adjuntos para OCR, visión y "Regenerar". */
    private fun reducirImagen(uri: Uri): String? {
        val limites = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, limites) }
        if (limites.outWidth <= 0 || limites.outHeight <= 0) return null
        var muestra = 1
        while (maxOf(limites.outWidth, limites.outHeight) / muestra > 1280) muestra *= 2
        val opciones = android.graphics.BitmapFactory.Options().apply { inSampleSize = muestra }
        val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opciones) } ?: return null
        val orientacion = try {
            contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) { android.media.ExifInterface.ORIENTATION_NORMAL }
        val grados = when (orientacion) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val final = if (grados != 0f) {
            android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(grados) }, true)
        } else bmp
        val dir = File(filesDir, "adjuntos").apply { mkdirs() }
        val f = File(dir, "img-${System.currentTimeMillis()}.jpg")
        java.io.FileOutputStream(f).use { final.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        return f.absolutePath
    }

    // ───────────────────────── Conversación ─────────────────────────

    private fun nueva() {
        if (generando) return
        if (mensajes.isNotEmpty()) guardarSesion()
        sesionId = UUID.randomUUID().toString()
        mensajes.clear()
        tituloSesion = "Nueva conversación"
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit().putString("sesion", sesionId).apply()
        quitarAdjuntos()
        motor.nuevaConversacion()
        if (modeloLlamaActual != null) lifecycleScope.launch { restaurarContextoLlama() }
        render(null)
        renderSidebar()
        drawer.closeDrawer(GravityCompat.START)
        tvEstado.text = "Nueva conversación"
        etMensaje.requestFocus()
    }

    private fun enviar(): Boolean {
        val texto = etMensaje.text.toString().trim()
        if ((texto.isEmpty() && adjuntos.isEmpty()) || generando) return false
        if (modeloActual == null && modeloLlamaActual == null) return false
        val pregunta = texto.ifEmpty { "Analiza lo adjunto: resume qué ocurre, señala los errores o problemas y propón soluciones." }

        // Texto guardado = bloques de adjuntos + marca de imagen (solo modelos con visión) + pregunta.
        val sb = StringBuilder()
        adjuntos.mapNotNull { it.bloque }.forEach { sb.append(it).append('\n') }
        if (modeloActual?.vision == true) adjuntos.firstOrNull { it.imagenRuta != null }?.let { sb.append("[[IMG:${it.imagenRuta}]]\n") }
        sb.append(pregunta)
        val guardado = sb.toString()

        etMensaje.setText("")
        quitarAdjuntos()
        mensajes.add("u" to guardado)
        render(null)
        generarDesde(guardado, guardar = true)
        return true
    }

    private fun detenerGeneracion() {
        tvEstado.text = "Deteniendo…"
        if (modeloLlamaActual != null) llamaEngine.detener() else jobTexto?.cancel()
    }

    /** Burbuja de la respuesta que se está escribiendo: se actualiza en sitio, sin reconstruir toda la lista. */
    private var burbujaStreaming: TextView? = null
    private var jobTexto: Job? = null

    /** Los modelos "razonadores" (DeepSeek-R1, Qwen3...) escriben su pensamiento entre <think>…</think>: se muestra solo la respuesta. */
    private fun visibleDeRespuesta(texto: String, finalizado: Boolean): String {
        val cierre = texto.lastIndexOf("</think>")
        if (cierre >= 0) {
            val resp = texto.substring(cierre + "</think>".length).trimStart()
            return when {
                resp.isNotEmpty() -> resp
                finalizado -> "💭 El modelo terminó de pensar pero no escribió respuesta."
                else -> "💭 Pensando…"
            }
        }
        if (texto.contains("<think>")) {
            val palabras = texto.substringAfter("<think>").trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            return if (finalizado) "💭 Se cortó mientras pensaba ($palabras palabras) y no llegó a responder. Prueba a repetir la pregunta."
                   else "💭 Pensando… ($palabras palabras)"
        }
        return texto
    }

    private suspend fun generarLlama(prompt: String) {
        val respuesta = StringBuilder()
        render("")                       // crea la burbuja en streaming
        var ultimoRender = 0L
        var trozos = 0
        val inicio = System.currentTimeMillis()
        try {
            llamaEngine.generar(prompt, 2048).collect { trozo ->
                respuesta.append(trozo)
                trozos++
                val ahora = System.currentTimeMillis()
                if (ahora - ultimoRender >= 120) {
                    ultimoRender = ahora
                    burbujaStreaming?.text = markdownBasico(visibleDeRespuesta(respuesta.toString(), false))
                    val seg = ((ahora - inicio) / 1000.0).coerceAtLeast(0.1)
                    tvEstado.text = "${modeloLlamaActual?.nombre ?: "Modelo"} · $trozos trozos · ${"%.1f".format(trozos / seg)}/s · ■ para detener"
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        } finally {
            if (respuesta.isNotEmpty()) mensajes.add("m" to respuesta.toString())   // se conserva aunque se detenga
            burbujaStreaming = null
        }
    }

    private suspend fun generarLiteRT(prompt: String, ruta: String?) {
        val respuesta = StringBuilder()
        var fallo: Throwable? = null
        render("")
        var ultimoRender = 0L
        val job = lifecycleScope.launch {
            try {
                motor.generar(prompt, ruta).collect { trozo ->
                    respuesta.append(trozo)
                    val ahora = System.currentTimeMillis()
                    if (ahora - ultimoRender >= 120) {
                        ultimoRender = ahora
                        burbujaStreaming?.text = markdownBasico(visibleDeRespuesta(respuesta.toString(), false))
                        tvEstado.text = "${modeloActual?.nombre ?: "Modelo"} · ${respuesta.length} caracteres · ■ para detener"
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fallo = e
            }
        }
        jobTexto = job
        try {
            job.join()
        } finally {
            jobTexto = null
            if (respuesta.isNotEmpty()) mensajes.add("m" to respuesta.toString())   // se conserva aunque se detenga
            burbujaStreaming = null
        }
        fallo?.let { throw it }
    }

    private fun actualizarBotonEnvio() {
        btnEnviar.text = if (generando) "■" else "↑"
        btnEnviar.contentDescription = if (generando) "Detener" else "Enviar"
        btnEnviar.isEnabled = generando || modeloActual != null || modeloLlamaActual != null
    }

    /** [guardado] es el texto del mensaje tal como se guarda (con marcas de adjuntos); aquí se convierte para el modelo. */
    private fun generarDesde(guardado: String, guardar: Boolean) {
        generando = true; actualizarBotonEnvio()
        val prompt = paraModelo(guardado)
        val ruta = regexImagen.find(guardado)?.groupValues?.get(1)?.takeIf { File(it).exists() && modeloActual?.vision == true }
        val nombreModelo = modeloLlamaActual?.nombre ?: modeloActual?.nombre ?: ""
        val backend = if (modeloLlamaActual != null) "llama.cpp" else "LiteRT"
        tvEstado.text = "Generando · $nombreModelo"
        DebugLog.log("GEN", "inicio · $backend · $nombreModelo · prompt=${prompt.length} car · imagen=${ruta != null} · ${DebugLog.mem()}")
        DebugLog.markStart("$backend · ${prompt.take(80)}")
        val inicio = System.currentTimeMillis()
        lifecycleScope.launch {
            try {
                if (modeloLlamaActual != null) generarLlama(prompt) else generarLiteRT(prompt, ruta)
                if (guardar) guardarSesion()
                render(null)
                DebugLog.log("GEN", "fin · $backend · ${System.currentTimeMillis() - inicio} ms · ${DebugLog.mem()}")
            } catch (e: CancellationException) {
                DebugLog.log("GEN", "cancelada · $backend"); throw e
            } catch (e: Exception) {
                DebugLog.log("GEN", "⚠ excepción $backend: ${e.javaClass.name}: ${e.message}")
                mensajes.add("m" to "Error: ${e.message ?: e.javaClass.simpleName}")
                render(null)
            } finally {
                progreso.visibility = View.GONE
                DebugLog.markEnd()
                generando = false
                actualizarBotonEnvio()
                tvEstado.text = "Listo · $nombreModelo"
            }
        }
    }

    override fun onDestroy() {
        llamaEngine.liberar()
        motor.liberar()
        super.onDestroy()
    }
}
