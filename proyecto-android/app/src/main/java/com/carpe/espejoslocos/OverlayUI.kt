package com.carpe.espejoslocos

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

object OverlayUI {

    // ScrollView con altura máxima: así el panel ⚙️ crece con el contenido
    // hasta la mitad de la pantalla y hace scroll a partir de ahí.
    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeight: Int = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    fun crear(
        activity: Activity,
        onCambiarFiltro: (Filtros.Filtro) -> Unit,
        onCapturar: () -> Unit,
        onCambiarCamara: () -> Unit,
        onReiniciarCamara: () -> Unit
    ): View {
        val root = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val nombreFiltro = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            text = "${MotorCamara.filtroActual.icono}  ${MotorCamara.filtroActual.nombre}"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 60
            }
        }
        root.addView(nombreFiltro)

        // ---- Contador de FPS (arriba a la derecha, oculto por defecto) ----
        val contadorFps = TextView(activity).apply {
            setTextColor(Color.parseColor("#7FE0BC"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(20, 8, 20, 8)
            text = "-- fps"
            visibility = if (ConfigCamara.mostrarFps) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 60
                marginEnd = 30
            }
        }
        root.addView(contadorFps)

        val handlerFps = Handler(Looper.getMainLooper())
        val runnableFps = object : Runnable {
            override fun run() {
                if (ConfigCamara.mostrarFps) {
                    val res = ConfigCamara.resolucion.etiqueta
                    contadorFps.text = "${MotorCamara.fpsActual.toInt()} fps · $res"
                }
                handlerFps.postDelayed(this, 500)
            }
        }
        handlerFps.post(runnableFps)

        // ---- Panel de ajustes ----
        val panelParams = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 10, 10, 20))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = 250
                marginStart = 30
                marginEnd = 30
            }
        }
        root.addView(panelParams)

        val alturaMaxPanel = (activity.resources.displayMetrics.heightPixels * 0.55).toInt()
        val scrollParams = MaxHeightScrollView(activity).apply {
            maxHeight = alturaMaxPanel
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val contParams = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 24, 30, 30)
        }
        scrollParams.addView(contParams)
        panelParams.addView(scrollParams)

        // ---- Slider de intensidad ----
        val slider = SeekBar(activity).apply {
            max = 100
            progress = (MotorCamara.intensidad * 100).toInt()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 180
                marginStart = 60
                marginEnd = 60
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    MotorCamara.intensidad = progress / 100f
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(slider)

        val panelLog = crearPanelLog(activity, root)

        // ---- Barra inferior ----
        val barra = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = 60
            }
        }

        fun boton(texto: String, tamano: Float, onClick: () -> Unit): Button {
            return Button(activity).apply {
                this.text = texto
                textSize = tamano
                setBackgroundColor(Color.argb(140, 0, 0, 0))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 6
                    marginEnd = 6
                }
                setOnClickListener { onClick() }
            }
        }

        val filtros = Filtros.lista
        var idx = filtros.indexOfFirst { it.id == MotorCamara.filtroActual.id }.coerceAtLeast(0)

        // ---- Helpers para pintar el panel ----

        fun tituloSeccion(texto: String): TextView = TextView(activity).apply {
            this.text = texto
            setTextColor(Color.parseColor("#B9B5D8"))
            textSize = 11f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 20, 0, 10)
        }

        fun separador(): View = View(activity).apply {
            setBackgroundColor(Color.parseColor("#3A3A5A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { topMargin = 16; bottomMargin = 8 }
        }

        fun filaOpciones(
            etiqueta: String,
            opciones: List<String>,
            idxSeleccionado: Int,
            onSeleccion: (Int) -> Unit
        ) {
            val titulo = TextView(activity).apply {
                text = etiqueta
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(0, 14, 0, 6)
            }
            contParams.addView(titulo)

            val fila = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            opciones.forEachIndexed { i, texto ->
                val activo = i == idxSeleccionado
                val b = Button(activity).apply {
                    text = texto
                    textSize = 12f
                    setPadding(8, 6, 8, 6)
                    setBackgroundColor(
                        if (activo) Color.parseColor("#6C5CE7")
                        else Color.argb(120, 60, 60, 90)
                    )
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 4
                        marginEnd = 4
                    }
                    setOnClickListener { onSeleccion(i) }
                }
                fila.addView(b)
            }
            contParams.addView(fila)
        }

        fun filaSiNo(etiqueta: String, valor: Boolean, onCambio: (Boolean) -> Unit) {
            filaOpciones(etiqueta, listOf("Sí", "No"), if (valor) 0 else 1) { i ->
                onCambio(i == 0)
            }
        }

        fun pintarPanelParams() {
            val filtro = filtros[idx]
            contParams.removeAllViews()

            // ===== Sección 1: filtro activo =====
            contParams.addView(tituloSeccion("FILTRO · ${filtro.nombre.uppercase()}"))

            if (filtro.parametros.isEmpty()) {
                val vacio = TextView(activity).apply {
                    text = "Este filtro no tiene parámetros extra."
                    setTextColor(Color.parseColor("#7A7796"))
                    textSize = 12f
                    setPadding(0, 4, 0, 4)
                }
                contParams.addView(vacio)
            } else {
                filtro.parametros.forEach { p ->
                    val valorActual = Filtros.getParam(filtro, p.id)

                    val cabecera = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 14, 0, 4)
                    }
                    val titulo = TextView(activity).apply {
                        text = p.etiqueta
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val valorTxt = TextView(activity).apply {
                        text = if (p.esEntero) valorActual.toInt().toString()
                               else String.format("%.2f", valorActual)
                        setTextColor(Color.parseColor("#9ADCFF"))
                        textSize = 13f
                        typeface = Typeface.MONOSPACE
                    }
                    cabecera.addView(titulo)
                    cabecera.addView(valorTxt)
                    contParams.addView(cabecera)

                    val sb = SeekBar(activity).apply {
                        max = 100
                        progress = ((valorActual - p.min) / (p.max - p.min) * 100f).toInt()
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s2: SeekBar?, progress: Int, fromUser: Boolean) {
                                var v = p.min + (p.max - p.min) * (progress / 100f)
                                if (p.esEntero) v = v.toInt().toFloat()
                                Filtros.setParam(filtro, p.id, v)
                                valorTxt.text = if (p.esEntero) v.toInt().toString()
                                                else String.format("%.2f", v)
                            }
                            override fun onStartTrackingTouch(s2: SeekBar?) {}
                            override fun onStopTrackingTouch(s2: SeekBar?) {}
                        })
                    }
                    contParams.addView(sb)
                }
            }

            contParams.addView(separador())

            // ===== Sección 2: cámara =====
            contParams.addView(tituloSeccion("CÁMARA"))

            filaOpciones(
                "Resolución",
                ConfigCamara.resoluciones.map { it.etiqueta },
                ConfigCamara.resoluciones.indexOf(ConfigCamara.resolucion)
            ) { i ->
                ConfigCamara.resolucion = ConfigCamara.resoluciones[i]
                Toast.makeText(activity, "Reiniciando cámara…", Toast.LENGTH_SHORT).show()
                onReiniciarCamara()
                pintarPanelParams()
            }

            filaOpciones(
                "FPS",
                listOf("Auto", "30", "60"),
                when (ConfigCamara.fps) { null -> 0; 30 -> 1; 60 -> 2; else -> 0 }
            ) { i ->
                ConfigCamara.fps = when (i) { 1 -> 30; 2 -> 60; else -> null }
                onReiniciarCamara()
                pintarPanelParams()
            }

            filaOpciones(
                "Interpolación",
                listOf("Lineal", "Cúbica"),
                if (ConfigCamara.interpolacion == org.opencv.imgproc.Imgproc.INTER_CUBIC) 1 else 0
            ) { i ->
                ConfigCamara.interpolacion =
                    if (i == 1) org.opencv.imgproc.Imgproc.INTER_CUBIC
                    else org.opencv.imgproc.Imgproc.INTER_LINEAR
                pintarPanelParams()
            }

            filaSiNo("Espejar cámara frontal", ConfigCamara.espejarFrontal) {
                ConfigCamara.espejarFrontal = it
                pintarPanelParams()
            }

            filaSiNo("Mostrar FPS en pantalla", ConfigCamara.mostrarFps) {
                ConfigCamara.mostrarFps = it
                contadorFps.visibility = if (it) View.VISIBLE else View.GONE
                pintarPanelParams()
            }

            val nota = TextView(activity).apply {
                text = "Los ajustes se guardan automáticamente."
                setTextColor(Color.parseColor("#7A7796"))
                textSize = 11f
                setPadding(0, 20, 0, 4)
            }
            contParams.addView(nota)
        }

        fun pintar() {
            nombreFiltro.text = "${filtros[idx].icono}  ${filtros[idx].nombre}"
            if (panelParams.visibility == View.VISIBLE) pintarPanelParams()
        }

        barra.addView(boton("◀", 24f) {
            idx = (idx - 1 + filtros.size) % filtros.size
            MotorCamara.filtroActual = filtros[idx]
            pintar()
            onCambiarFiltro(filtros[idx])
        })
        barra.addView(boton("⚙️", 22f) {
            if (panelParams.visibility == View.VISIBLE) {
                panelParams.visibility = View.GONE
            } else {
                pintarPanelParams()
                panelParams.visibility = View.VISIBLE
            }
        })
        barra.addView(boton("📸", 24f) { onCapturar() })
        barra.addView(boton("🐞", 24f) {
            panelLog.visibility = if (panelLog.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (panelLog.visibility == View.VISIBLE) panelLog.bringToFront()
        })
        barra.addView(boton("🔄", 24f) { onCambiarCamara() })
        barra.addView(boton("▶", 24f) {
            idx = (idx + 1) % filtros.size
            MotorCamara.filtroActual = filtros[idx]
            pintar()
            onCambiarFiltro(filtros[idx])
        })

        root.addView(barra)
        return root
    }

    private fun crearPanelLog(activity: Activity, padre: FrameLayout): View {
        val contenedor = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 10, 10, 20))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = 160
                bottomMargin = 260
                marginStart = 30
                marginEnd = 30
            }
        }

        val cab = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val titulo = TextView(activity).apply {
            text = "🐞 Log de diagnóstico"
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        cab.addView(titulo)

        fun botonPequeno(texto: String, onClick: () -> Unit): Button {
            return Button(activity).apply {
                this.text = texto
                textSize = 13f
                setBackgroundColor(Color.argb(200, 60, 60, 90))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 6 }
                setOnClickListener { onClick() }
            }
        }
        cab.addView(botonPequeno("📋 Copiar") {
            val texto = LogEspejos.textoCompleto()
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("EspejosLocos log", texto))
            Toast.makeText(activity, "Log copiado", Toast.LENGTH_SHORT).show()
        })
        cab.addView(botonPequeno("🗑") { LogEspejos.limpiar() })
        cab.addView(botonPequeno("✕") { contenedor.visibility = View.GONE })
        contenedor.addView(cab)

        val texto = TextView(activity).apply {
            setTextColor(Color.parseColor("#CFCFEA"))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setPadding(20, 10, 20, 20)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(texto)
        }
        contenedor.addView(scroll)

        LogEspejos.setListener {
            activity.runOnUiThread {
                texto.text = LogEspejos.textoCompleto()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        texto.text = LogEspejos.textoCompleto()

        padre.addView(contenedor)
        return contenedor
    }
}