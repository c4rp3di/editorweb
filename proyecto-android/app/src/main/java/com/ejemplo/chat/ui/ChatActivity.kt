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
import com.ejemplo.chat.ia.flux.Flux2KleinGenerator
import com.ejemplo.chat.ia.flux.Flux2VaeStats
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
        btnMotor.text = textoMotor()
        btnMotor.setOnClickListener {
            if (generando) return@setOnClickListener
            val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
            // Cicla entre los 3 modos: CPU puro -> CPU + kc en GPU (recomendado) -> GPU -> CPU puro.
            val (gpu, gpuKc, msg) = when {
                usarGpu() -> Triple(false, false, "CPU puro: en este móvil kc_prep consume ~6,7 GB al compilar y el sistema cierra la app. Modo no recomendado.")
                usarGpuKc() -> Triple(true, true, "GPU: más rápida, pero en algunos móviles (Xiaomi) el sistema cierra la app por memoria de GPU (>1,5 GB).")
                else -> Triple(false, true, "CPU + kc en GPU: recomendado para este móvil. Solo ~185 MB de GPU.")
            }
            prefs.edit().putBoolean("flux_gpu", gpu).putBoolean("flux_gpu_small", gpuKc).apply()
            btnMotor.text = textoMotor()
            DebugLog.log("UI", "Motor de imagen: GPU=$gpu · kc en GPU=$gpuKc")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
        val imagenGuardada = modeloImagenActual
        if (imagenGuardada != null) {
            activarModeloImagen(imagenGuardada)
        } else if (guardado != null && motor.modeloDescargado(guardado)) preparar(guardado) else {
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
            text = if (id == sesionId) "\u25cf $title" else "  $title"
                setAllCaps(false)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener {
                    if (generando) return@setOnClickListener
                    cargarSesion(id)
                    render(null)
                    drawer.closeDrawer(GravityCompat.START)
                    if (modeloActual != null) motor.nuevaConversacion(historialTexto())
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
        if (imagenEnCurso) agregarCajaProgreso()
        if (parcial != null) agregarBurbuja("m", parcial, true, mensajes.size)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resolveTextColor(): Int = if ((resources.configuration.uiMode and 0x30) == 0x20) Color.WHITE else Color.rgb(24,24,27)

    private fun usarGpu() =
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).getBoolean("flux_gpu", false)

    /** kc_prep/kc_final se compilan en GPU aunque el resto vaya en CPU.
     *  En CPU puro XNNPACK infla kc_prep a ~6,7 GB y el sistema cierra la app (LOW_MEMORY). */
    private fun usarGpuKc() =
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).getBoolean("flux_gpu_small", true)

    private fun textoMotor() = when {
        usarGpu() -> "⚙  Motor de imagen: GPU (rápido, puede cerrarse)"
        usarGpuKc() -> "⚙  Motor: CPU + kc en GPU (recomendado)"
        else -> "⚙  Motor de imagen: CPU puro (lento, riesgo de cierre)"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun textoCaja(): String {
        val s = (System.currentTimeMillis() - inicioImagen) / 1000
        return "$ultimoEstado\n⏱ ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Caja bajo el prompt: título, etapa actual, barra de progreso, tiempo y botón Cancelar. */
    private fun agregarCajaProgreso() {
        val color = resolveTextColor()
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_model)
            setPadding(dp(16), dp(14), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.86f).toInt(), -2)
                .apply { setMargins(dp(6), dp(6), dp(6), dp(2)) }
        }
        val titulo = TextView(this).apply {
            text = "🖼  Generando imagen…"
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
        }
        val estado = TextView(this).apply {
            text = textoCaja()
            textSize = 13f
            setTextColor(color)
            setPadding(0, dp(6), 0, dp(8))
        }
        val barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progress = (ultimaFrac * 1000).toInt()
        }
        val cancelar = MaterialButton(this).apply {
            text = "Cancelar"
            setAllCaps(false)
            minHeight = dp(34)
            setOnClickListener {
                fluxGenerator?.cancel()
                estado.text = "Cancelando… (termina el bloque en curso)"
            }
        }
        caja.addView(titulo)
        caja.addView(estado)
        caja.addView(barra, LinearLayout.LayoutParams(-1, dp(8)))
        caja.addView(cancelar)
        cajaTexto = estado
        cajaBarra = barra
        contenedor.addView(caja)
    }

    private fun actualizarProgresoImagen(texto: String, frac: Float) {
        ultimoEstado = texto
        ultimaFrac = frac
        cajaTexto?.text = textoCaja()
        cajaBarra?.progress = (frac * 1000).toInt()
        tvEstado.text = "Imagen · $texto · ${(frac * 100).toInt()}%"
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
        if (ultima.first == "m" && ultima.second.startsWith("[[IMAGE]]")) {
            if (modeloImagenActual == null) return
            val prompt = mensajes[ultimoUsuario].second
            while (mensajes.size > ultimoUsuario + 1) mensajes.removeAt(mensajes.lastIndex)
            render(null)
            generarImagenDesde(prompt)
            return
        }
        if (modeloActual == null) return
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
            is Entrada.Imagen ->
                if (imagenes.estadisticasVae(e.m)) activarModeloImagen(e.m) else ofrecerEstadisticasVae(e.m)
        }
    }

    /** Descarga mínima (~KB) que solo se hace con confirmación explícita. */
    private fun ofrecerEstadisticasVae(m: ModelosImagen.ModeloImagen) {
        AlertDialog.Builder(this)
            .setTitle("Falta un dato del VAE")
            .setMessage("Para que los colores salgan bien hace falta una descarga muy pequeña (unos KB) con las estadísticas del VAE. Solo se descarga si la confirmas.")
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Usar sin él") { _, _ -> activarModeloImagen(m) }
            .setPositiveButton("Descargar y usar") { _, _ ->
                lifecycleScope.launch {
                    try {
                        tvEstado.text = "Descargando estadísticas del VAE…"
                        withContext(Dispatchers.IO) { Flux2VaeStats.download(imagenes.carpeta(m)) }
                        activarModeloImagen(m)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        tvEstado.text = "No se pudieron descargar las estadísticas del VAE"
                        Toast.makeText(
                            this@ChatActivity,
                            "${ex.message ?: ex.javaClass.simpleName}. Puedes usar el modelo sin ellas (colores aproximados).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.show()
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
        DebugLog.log("UI", "Modelo de imagen activado: ${m.nombre}")
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
        imagenEnCurso = true
        inicioImagen = System.currentTimeMillis()
        ultimoEstado = "Preparando…"
        ultimaFrac = 0f
        actualizarBotonEnvio()
        tvEstado.text = "Generando imagen local…"
        render(null)
        DebugLog.markStart(prompt)
        DebugLog.log("UI", "Generar imagen: \"${prompt.take(80)}\" · ${DebugLog.mem()}")
        ticker = lifecycleScope.launch {
            while (imagenEnCurso) {
                cajaTexto?.text = textoCaja()
                delay(1000)
            }
        }
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    val generator = fluxGenerator ?: Flux2KleinGenerator(this@ChatActivity).also { fluxGenerator = it }
                    generator.generate(prompt) { estado, frac ->
                        runOnUiThread { actualizarProgresoImagen(estado, frac) }
                    }
                }
                val file = File(filesDir, "generadas").apply { mkdirs() }
                    .let { File(it, "flux_${System.currentTimeMillis()}.png") }
                withContext(Dispatchers.IO) {
                    java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                mensajes.add("m" to "[[IMAGE]]${file.absolutePath}")
                guardarSesion()
                val aviso = fluxGenerator?.lastWarning
                tvEstado.text = "Imagen generada · ${modeloImagenActual?.nombre ?: "FLUX"}" +
                    (if (aviso != null) " · $aviso" else "")
            } catch (e: CancellationException) {
                // Si la corrutina sigue activa, la cancelación la pidió el usuario con el botón.
                if (!isActive) throw e
                DebugLog.log("UI", "Generación cancelada por el usuario")
                mensajes.add("m" to "Generación cancelada.")
                guardarSesion()
                tvEstado.text = "Generación cancelada"
            } catch (e: Throwable) {
                val reason = e.message ?: e.javaClass.simpleName
                DebugLog.log("ERROR", "${e.javaClass.simpleName}: ${e.stackTraceToString().take(1500)}")
                mensajes.add("m" to "No se pudo generar la imagen: $reason")
                guardarSesion()
                tvEstado.text = "No se pudo generar la imagen"
                Toast.makeText(this@ChatActivity, reason, Toast.LENGTH_LONG).show()
            } finally {
                imagenEnCurso = false
                ticker?.cancel()
                DebugLog.markEnd()
                generando = false
                actualizarBotonEnvio()
                if (isActive) render(null)
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
        // close() cancela y difiere el cierre del entorno nativo si hay una generación en curso.
        fluxGenerator?.close()
        fluxGenerator = null
        motor.liberar()
        super.onDestroy()
    }
}
