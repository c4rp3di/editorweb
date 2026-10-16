package com.imagen.chat.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.imagen.chat.R
import com.imagen.chat.ia.DebugLog
import com.imagen.chat.ia.DiffusionNative
import com.imagen.chat.ia.GenClient
import com.imagen.chat.ia.GenDetenida
import com.imagen.chat.ia.GenProtocolo
import com.imagen.chat.ia.ModelosImagen
import com.imagen.chat.ia.SeguimientoProgreso
import com.imagen.chat.ia.files.CreatedFile
import com.imagen.chat.ia.files.CreatedFilesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class ChatActivity : AppCompatActivity() {
    private lateinit var imagenes: ModelosImagen
    private lateinit var createdFiles: CreatedFilesManager
    private lateinit var contenedor: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var progreso: ProgressBar
    private lateinit var etPrompt: EditText
    private lateinit var etNegativo: EditText
    private lateinit var btnGenerar: MaterialButton
    private lateinit var btnModelo: MaterialButton
    private lateinit var btnAvanzado: MaterialButton
    private lateinit var tvEstado: TextView
    private lateinit var tvSemilla: TextView
    private lateinit var btnArchivos: MaterialButton
    private lateinit var btnDepuracion: MaterialButton

    private var modeloActual: ModelosImagen.ModeloImagen? = null
    private var generando = false
    private var sesionId = "actual"

    private var steps = 20
    private var cfg = 7.5f
    private var sharpness = 0.8f
    private var sampler = 0
    private var semilla = -1L
    private var ancho = 512
    private var alto = 512

    private var imagenEnCurso = false
    private var inicioImagen = 0L
    private var ultimoEstado = ""
    private var ticker: Job? = null

    companion object {
        private const val NEGATIVO_POR_DEFECTO =
            "borroso, desenfocado, baja calidad, baja resolución, ruido, artefactos, " +
            "manchas, deforme, desproporcionado, manos deformes, dedos extra, " +
            "marca de agua, firma, texto, letras, logotipo, " +
            "feo, horroroso, mutilado, amputado, mal dibujado, " +
            "fotografía sobreexpuesta, colores saturados en exceso"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        DebugLog.init(this)
        DiffusionNative.estaDisponible()
        imagenes = ModelosImagen(this)
        createdFiles = CreatedFilesManager(this)
        DebugLog.log("APP", "catálogo imagen=${ModelosImagen.MODELOS.size} · ${DebugLog.mem()}")

        contenedor = findViewById(R.id.contenedorMensajes)
        scroll = findViewById(R.id.scrollChat)
        progreso = findViewById(R.id.progreso)
        etPrompt = findViewById(R.id.etPrompt)
        etNegativo = findViewById(R.id.etNegativo)
        btnGenerar = findViewById(R.id.btnGenerar)
        btnModelo = findViewById(R.id.btnModelo)
        btnAvanzado = findViewById(R.id.btnAvanzado)
        tvEstado = findViewById(R.id.tvEstado)
        tvSemilla = findViewById(R.id.tvSemilla)
        btnArchivos = findViewById(R.id.btnArchivos)
        btnDepuracion = findViewById(R.id.btnDepuracion)

        etNegativo.setText(NEGATIVO_POR_DEFECTO)

        btnGenerar.setOnClickListener {
            if (generando) detenerGeneracion() else generar()
        }
        btnModelo.setOnClickListener { elegirModelo() }
        btnAvanzado.setOnClickListener { mostrarAvanzado() }
        btnArchivos.setOnClickListener {
            startActivity(Intent(this, CreatedFilesActivity::class.java))
        }
        btnDepuracion.setOnClickListener { mostrarDepuracion() }
        tvSemilla.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("semilla", tvSemilla.text))
            Toast.makeText(this, "Semilla copiada", Toast.LENGTH_SHORT).show()
        }

        val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
        steps = prefs.getInt("steps", 20)
        cfg = prefs.getFloat("cfg", 7.5f)
        sharpness = prefs.getFloat("sharpness", 0.8f)
        sampler = prefs.getInt("sampler", 0)
        ancho = prefs.getInt("ancho", 512)
        alto = prefs.getInt("alto", 512)
        semilla = prefs.getLong("semilla", -1L)

        val idGuardado = prefs.getString("modelo_imagen", null)
        modeloActual = ModelosImagen.MODELOS.firstOrNull { it.id == idGuardado && imagenes.descargado(it) }
        actualizarBotonGenerar()

        if (modeloActual == null) {
            tvEstado.text = "Elige y descarga el modelo SD 1.5"
            contenedor.postDelayed({ elegirModelo() }, 300)
        } else {
            tvEstado.text = "${modeloActual!!.nombre} · listo"
        }
        renderEstadoInicial()
        actualizarSemilla()
    }

    private fun renderEstadoInicial() {
        contenedor.removeAllViews()
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 80, 32, 32)
        }
        empty.addView(TextView(this).apply {
            text = "Genera imágenes locales"
            textSize = 26f
            setTextColor(resolveTextColor())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        empty.addView(TextView(this).apply {
            text = "Stable Diffusion 1.5 · privado y sin conexión"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 0)
        })
        contenedor.addView(empty)
    }

    private fun resolveTextColor(): Int =
        if ((resources.configuration.uiMode and 0x30) == 0x20) Color.WHITE else Color.rgb(24, 24, 27)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun actualizarSemilla() {
        tvSemilla.text = if (semilla < 0) "🎲 Semilla aleatoria" else "🌱 Semilla: $semilla"
    }

    private fun actualizarBotonGenerar() {
        btnGenerar.text = if (generando) "■" else "Generar"
        btnGenerar.isEnabled = when {
            generando -> true
            modeloActual == null -> false
            !DiffusionNative.estaDisponible() -> false
            else -> true
        }
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

    private fun mostrarAvanzado() {
        val hoja = BottomSheetDialog(this)
        val colorPrincipal = colorAtributo(android.R.attr.textColorPrimary, Color.WHITE)
        val colorSecundario = colorAtributo(android.R.attr.textColorSecondary, Color.GRAY)
        val acento = Color.parseColor("#6366F1")

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        raiz.addView(TextView(this).apply {
            text = "Parámetros avanzados"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrincipal)
        })
        raiz.addView(TextView(this).apply {
            text = "Ajustes finos de difusión. Los valores por defecto funcionan bien."
            textSize = 12f
            setTextColor(colorSecundario)
            setPadding(0, dp(4), 0, dp(16))
        })

        fun filaDeslizante(etiqueta: String, valorActual: Float, min: Float, max: Float, paso: Float,
                           formatear: (Float) -> String, onChange: (Float) -> Unit) {
            val grupo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val cab = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val lbl = TextView(this).apply { text = etiqueta; textSize = 14f; setTextColor(colorPrincipal) }
            val valTv = TextView(this).apply {
                text = formatear(valorActual); textSize = 14f; setTextColor(acento); typeface = Typeface.MONOSPACE
            }
            cab.addView(lbl, LinearLayout.LayoutParams(0, -2, 1f))
            cab.addView(valTv)
            val seek = SeekBar(this).apply {
                this.max = ((max - min) / paso).toInt()
                progress = ((valorActual - min) / paso).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = min + p * paso
                        valTv.text = formatear(v)
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            grupo.addView(cab)
            grupo.addView(seek)
            raiz.addView(grupo)
        }

        filaDeslizante("Pasos de muestreo", steps.toFloat(), 10f, 60f, 1f, { "${it.toInt()}" }) {
            steps = it.toInt()
        }
        filaDeslizante("CFG (guía del prompt)", cfg, 1.0f, 15.0f, 0.5f, { "%.1f".format(it) }) {
            cfg = it
        }
        filaDeslizante("Nitidez (unsharp mask)", sharpness, 0.0f, 2.0f, 0.1f, { "%.1f".format(it) }) {
            sharpness = it
        }
        filaDeslizante("Ancho", ancho.toFloat(), 256f, 768f, 64f, { "${it.toInt()} px" }) {
            ancho = it.toInt()
        }
        filaDeslizante("Alto", alto.toFloat(), 256f, 768f, 64f, { "${it.toInt()} px" }) {
            alto = it.toInt()
        }

        val samplerGrupo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        samplerGrupo.addView(TextView(this).apply {
            text = "Muestreador"; textSize = 14f; setTextColor(colorPrincipal)
        })
        val radios = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbKarras = RadioButton(this).apply { text = "Euler-A · Karras (mejor calidad)"; textSize = 13f; setTextColor(colorPrincipal); id = View.generateViewId() }
        val rbDiscreto = RadioButton(this).apply { text = "Euler · Discreto (más rápido)"; textSize = 13f; setTextColor(colorPrincipal); id = View.generateViewId() }
        if (sampler == 0) rbKarras.isChecked = true else rbDiscreto.isChecked = true
        radios.addView(rbKarras); radios.addView(rbDiscreto)
        radios.setOnCheckedChangeListener { _, id ->
            sampler = if (id == rbKarras.id) 0 else 1
        }
        samplerGrupo.addView(radios)
        raiz.addView(samplerGrupo)

        val semGrupo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        semGrupo.addView(TextView(this).apply {
            text = "Semilla (−1 = aleatoria)"; textSize = 14f; setTextColor(colorPrincipal)
        })
        val semFila = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val semInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(semilla.toString())
            textSize = 14f
            setTextColor(colorPrincipal)
        }
        val btnRandom = MaterialButton(this).apply {
            text = "🎲"; setOnClickListener {
                semilla = -1L
                semInput.setText("-1")
            }
        }
        semFila.addView(semInput, LinearLayout.LayoutParams(0, -2, 1f))
        semFila.addView(btnRandom)
        semGrupo.addView(semFila)
        raiz.addView(semGrupo)

        val sv = ScrollView(this)
        sv.addView(raiz)
        hoja.setContentView(sv)
        hoja.setOnShowListener { hoja.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        hoja.setOnDismissListener {
            semilla = semInput.text.toString().toLongOrNull() ?: -1L
            val prefs = getSharedPreferences("chat_local", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("steps", steps).putFloat("cfg", cfg).putFloat("sharpness", sharpness)
                .putInt("sampler", sampler).putInt("ancho", ancho).putInt("alto", alto)
                .putLong("semilla", semilla)
                .apply()
            actualizarSemilla()
            Toast.makeText(this, "Parámetros guardados", Toast.LENGTH_SHORT).show()
        }
        hoja.show()
    }

    private fun colorAtributo(attr: Int, defecto: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        return try { ta.getColor(0, defecto) } finally { ta.recycle() }
    }

    private fun elegirModelo() {
        if (generando) return
        val hoja = BottomSheetDialog(this)
        val colorPrincipal = colorAtributo(android.R.attr.textColorPrimary, Color.WHITE)
        val colorSecundario = colorAtributo(android.R.attr.textColorSecondary, Color.GRAY)
        val acento = Color.parseColor("#6366F1")

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        raiz.addView(TextView(this).apply {
            text = "Modelo de imagen"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrincipal)
        })

        ModelosImagen.MODELOS.forEach { m ->
            val activa = modeloActual?.id == m.id
            val descargada = imagenes.descargado(m)
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor(if (activa) "#226366F1" else "#14808080"))
                    if (activa) setStroke(dp(1), acento)
                }
                isClickable = true
                setOnClickListener {
                    hoja.dismiss()
                    if (descargada) usar(m) else confirmarDescarga(m)
                }
            }
            fila.addView(TextView(this).apply {
                text = m.nombre
                textSize = 16f
                setTextColor(colorPrincipal)
                typeface = Typeface.DEFAULT_BOLD
            })
            fila.addView(TextView(this).apply {
                text = m.descripcion
                textSize = 13f
                setTextColor(colorSecundario)
                setPadding(0, dp(3), 0, 0)
            })
            fila.addView(TextView(this).apply {
                val tamaño = m.artefactos.sumOf { it.expectedBytes } / 1_000_000
                text = when {
                    activa && descargada -> "✓ activo · $tamaño MB"
                    descargada -> "✓ descargado · toca para usar · $tamaño MB"
                    else -> "$tamaño MB · descarga manual"
                }
                textSize = 12f
                setTextColor(if (descargada) acento else colorSecundario)
                setPadding(0, dp(4), 0, 0)
            })
            if (descargada && !activa) {
                fila.addView(TextView(this).apply {
                    text = "🗑 Borrar pesos"
                    textSize = 12f
                    setTextColor(Color.parseColor("#E05A5A"))
                    setPadding(0, dp(8), 0, 0)
                    setOnClickListener {
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle("Borrar pesos")
                            .setMessage("¿Borrar los pesos descargados de ${m.nombre}? Tendrás que volver a descargarlos.")
                            .setPositiveButton("Borrar") { _, _ ->
                                imagenes.eliminar(m)
                                DebugLog.log("DIFFUSION", "pesos borrados · ${m.id}")
                                Toast.makeText(this@ChatActivity, "Pesos borrados", Toast.LENGTH_SHORT).show()
                                elegirModelo()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                })
            }
            raiz.addView(fila, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        val sv = ScrollView(this)
        sv.addView(raiz)
        hoja.setContentView(sv)
        hoja.setOnShowListener { hoja.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        hoja.show()
    }

    private fun confirmarDescarga(m: ModelosImagen.ModeloImagen) {
        val tamaño = m.artefactos.sumOf { it.expectedBytes } / 1_000_000
        val libre = imagenes.espacioLibreBytes() / 1_000_000
        AlertDialog.Builder(this)
            .setTitle("Descargar ${m.nombre}")
            .setMessage(
                "Peso: $tamaño MB.\n" +
                "Espacio libre: $libre MB.\n\n" +
                "Se verifica el SHA-256 antes de activar el modelo. ¿Continuar?"
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Descargar") { _, _ ->
                lifecycleScope.launch {
                    try {
                        progreso.visibility = View.VISIBLE
                        progreso.isIndeterminate = false
                        progreso.progress = 0
                        tvEstado.text = "Descargando ${m.nombre}…"
                        withContext(Dispatchers.IO) {
                            imagenes.descargar(m) { p -> runOnUiThread { progreso.progress = p } }
                        }
                        progreso.visibility = View.GONE
                        DebugLog.log("DIFFUSION", "pesos verificados · ${m.id}")
                        Toast.makeText(this@ChatActivity, "Pesos verificados", Toast.LENGTH_SHORT).show()
                        elegirModelo()
                    } catch (ce: CancellationException) {
                        progreso.visibility = View.GONE
                        throw ce
                    } catch (ex: Exception) {
                        progreso.visibility = View.GONE
                        DebugLog.log("DIFFUSION", "⚠ descarga/verificación: ${ex.message}")
                        Toast.makeText(this@ChatActivity, ex.message ?: "No se pudo descargar", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun usar(m: ModelosImagen.ModeloImagen) {
        if (!imagenes.descargado(m)) {
            Toast.makeText(this, "Primero hay que descargar ${m.nombre}", Toast.LENGTH_LONG).show()
            return
        }
        if (!DiffusionNative.estaDisponible()) {
            Toast.makeText(this, "libchatpro-diffusion.so no está disponible en este APK/dispositivo", Toast.LENGTH_LONG).show()
            return
        }
        modeloActual = m
        DebugLog.log("DIFFUSION", "modelo activado · ${m.id} · ${imagenes.carpeta(m).absolutePath}")
        getSharedPreferences("chat_local", Context.MODE_PRIVATE).edit()
            .putString("modelo_imagen", m.id).apply()
        btnModelo.text = m.nombre
        tvEstado.text = "${m.nombre} · listo"
        actualizarBotonGenerar()
    }

    private fun detenerGeneracion() {
        tvEstado.text = "Deteniendo…"
        GenClient.detener()
    }

    private fun generar() {
        val prompt = etPrompt.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Escribe un prompt", Toast.LENGTH_SHORT).show()
            return
        }
        val m = modeloActual ?: run {
            Toast.makeText(this, "Elige un modelo primero", Toast.LENGTH_SHORT).show()
            return
        }
        if (generando) return

        val negativo = etNegativo.text.toString().trim().ifBlank { NEGATIVO_POR_DEFECTO }
        generando = true
        imagenEnCurso = true
        inicioImagen = System.currentTimeMillis()
        ultimoEstado = "preparando"
        actualizarBotonGenerar()
        tvEstado.text = "Generando…"
        DebugLog.log("GEN", "inicio · ${m.nombre} · prompt=${prompt.length} · negativo=${negativo.length} · ${ancho}x${alto} · pasos=$steps cfg=$cfg nitidez=$sharpness sampler=$sampler")
        DebugLog.markStart(prompt)

        val png = File(
            createdFiles.directory(CreatedFile.Kind.IMAGE),
            "imagen-${System.currentTimeMillis()}.png"
        )

        ticker = lifecycleScope.launch {
            val seguimiento = SeguimientoProgreso(steps)
            progreso.visibility = View.VISIBLE
            progreso.isIndeterminate = true
            var seg = 0
            while (isActive) {
                delay(1000)
                seg++
                val p = GenClient.progreso()
                val e = seguimiento.actualizar(p, seg)
                ultimoEstado = e.texto
                tvEstado.text = "${m.nombre} · ${e.texto}"
                if (p.fase >= 2 || e.porcentaje > 5) {
                    progreso.isIndeterminate = false
                    progreso.max = 100
                    progreso.progress = e.porcentaje
                } else progreso.isIndeterminate = true
                if (seg % 5 == 0) {
                    DebugLog.log("GEN", "t=${seg}s · ${e.texto} · fase=${p.fase} paso=${p.paso}/${p.pasos} · gen RSS ${GenClient.rssGenMb()} MB · app ${DebugLog.mem()}")
                }
            }
        }

        val semillaEfectiva = if (semilla < 0) Random.nextLong(0, Long.MAX_VALUE) else semilla

        lifecycleScope.launch {
            try {
                val verificacion = withContext(Dispatchers.IO) { imagenes.verificacion(m) }
                if (!verificacion.ok) {
                    throw IllegalStateException(verificacion.message)
                }
                val modelPath = File(imagenes.carpeta(m), m.artefactos.first().fileName).absolutePath

                GenClient.ejecutar(
                    this@ChatActivity,
                    GenProtocolo.imagen(
                        modelPath = modelPath,
                        vaePath = "",
                        prompt = prompt,
                        negativePrompt = negativo,
                        outputPath = png.absolutePath,
                        width = ancho,
                        height = alto,
                        steps = steps,
                        seed = semillaEfectiva,
                        cfg = cfg,
                        sharpness = sharpness,
                        sampler = sampler
                    )
                )

                if (!png.isFile || png.length() == 0L) {
                    throw IllegalStateException("La generación terminó sin PNG")
                }

                val item = createdFiles.register(
                    source = png,
                    mimeType = "image/png",
                    kind = CreatedFile.Kind.IMAGE,
                    conversationId = sesionId,
                    prompt = prompt,
                    negativePrompt = negativo,
                    model = m.nombre,
                    seed = semillaEfectiva,
                    steps = steps,
                    cfg = cfg,
                    width = ancho,
                    height = alto
                )
                DebugLog.log("FILES", "imagen registrada · id=${item.id} · ${item.path} · ${item.sizeBytes} bytes")
                anadirImagenAlChat(item, prompt)

                if (semilla == -1L) {
                    semilla = semillaEfectiva
                    actualizarSemilla()
                }
            } catch (ce: CancellationException) {
                DebugLog.log("GEN", "cancelada")
                throw ce
            } catch (e: GenDetenida) {
                DebugLog.log("GEN", "detenida por el usuario")
                Toast.makeText(this@ChatActivity, "Generación detenida", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                DebugLog.log("GEN", "⚠ excepción: ${e.javaClass.name}: ${e.message}")
                anadirMensajeError(e.message ?: e.javaClass.simpleName)
            } finally {
                ticker?.cancel()
                ticker = null
                progreso.visibility = View.GONE
                DebugLog.markEnd()
                generando = false
                imagenEnCurso = false
                actualizarBotonGenerar()
                tvEstado.text = "${m.nombre} · listo"
            }
        }
    }

    private fun anadirImagenAlChat(item: CreatedFile, prompt: String) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        fila.addView(TextView(this).apply {
            text = "📝 $prompt"
            textSize = 13f
            setTextColor(resolveTextColor())
            setPadding(0, 0, 0, dp(6))
        })
        val imageView = ImageView(this).apply {
            setImageURI(Uri.fromFile(File(item.path)))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            contentDescription = "Imagen generada: $prompt"
            setOnClickListener {
                startActivity(Intent(this@ChatActivity, CreatedFilesActivity::class.java))
            }
        }
        fila.addView(imageView)
        val meta = "🌱 ${item.seed ?: "?"} · ⚙ ${item.steps ?: "?"} pasos · 🎯 CFG ${item.cfg ?: "?"} · ${item.width}x${item.height}"
        fila.addView(TextView(this).apply {
            text = meta
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, 0)
        })
        val acciones = LinearLayout(this).apply { gravity = Gravity.START }
        acciones.addView(MaterialButton(this).apply {
            text = "Compartir"
            setAllCaps(false)
            minHeight = dp(36)
            setOnClickListener { compartir(item) }
        })
        acciones.addView(MaterialButton(this).apply {
            text = "Reusar prompt"
            setAllCaps(false)
            minHeight = dp(36)
            setOnClickListener {
                etPrompt.setText(prompt)
                item.negativePrompt?.let { etNegativo.setText(it) }
                item.seed?.let { semilla = it; actualizarSemilla() }
                item.steps?.let { steps = it }
                item.cfg?.let { cfg = it }
                Toast.makeText(this@ChatActivity, "Parámetros cargados en el formulario", Toast.LENGTH_SHORT).show()
            }
        })
        acciones.addView(MaterialButton(this).apply {
            text = "Regenerar"
            setAllCaps(false)
            minHeight = dp(36)
            setOnClickListener {
                etPrompt.setText(prompt)
                item.negativePrompt?.let { etNegativo.setText(it) }
                semilla = -1L
                actualizarSemilla()
                generar()
            }
        })
        fila.addView(acciones)

        if (contenedor.childCount == 1 && contenedor.getChildAt(0) is LinearLayout) {
            contenedor.removeAllViews()
        }
        contenedor.addView(fila, 0)
        scroll.post { scroll.fullScroll(View.FOCUS_UP) }
    }

    private fun anadirMensajeError(mensaje: String) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#33E05A5A"))
                setStroke(dp(1), Color.parseColor("#E05A5A"))
            }
        }
        fila.addView(TextView(this).apply {
            text = "⚠️ Error"
            textSize = 14f
            setTextColor(Color.parseColor("#E05A5A"))
            typeface = Typeface.DEFAULT_BOLD
        })
        fila.addView(TextView(this).apply {
            text = mensaje
            textSize = 12f
            setTextColor(resolveTextColor())
            setPadding(0, dp(4), 0, 0)
        })
        contenedor.addView(fila, 0)
        scroll.post { scroll.fullScroll(View.FOCUS_UP) }
    }

    private fun compartir(item: CreatedFile) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", File(item.path)
            )
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartir imagen"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLog.log("MEM", "onTrimMemory nivel=$level · ${DebugLog.mem()}")
    }
}