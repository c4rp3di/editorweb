package com.carpe.espejoslocos

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
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

    fun crear(
        activity: Activity,
        onCambiarFiltro: (Filtros.Filtro) -> Unit,
        onCapturar: () -> Unit,
        onCambiarCamara: () -> Unit
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

        val slider = SeekBar(activity).apply {
            max = 100
            progress = (MotorCamara.intensidad * 100).toInt()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 220
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

        // ---- Panel de log (oculto por defecto) ----
        val panelLog = crearPanelLog(activity, root)

        // ---- Barra de botones ----
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
                    marginStart = 8
                    marginEnd = 8
                }
                setOnClickListener { onClick() }
            }
        }

        val filtros = Filtros.lista
        var idx = filtros.indexOfFirst { it.id == MotorCamara.filtroActual.id }.coerceAtLeast(0)

        fun pintar() {
            nombreFiltro.text = "${filtros[idx].icono}  ${filtros[idx].nombre}"
        }

        barra.addView(boton("◀", 26f) {
            idx = (idx - 1 + filtros.size) % filtros.size
            MotorCamara.filtroActual = filtros[idx]
            pintar()
            onCambiarFiltro(filtros[idx])
        })
        barra.addView(boton("📸", 26f) { onCapturar() })
        barra.addView(boton("🐞", 26f) {
            panelLog.visibility = if (panelLog.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (panelLog.visibility == View.VISIBLE) panelLog.bringToFront()
        })
        barra.addView(boton("🔄", 26f) { onCambiarCamara() })
        barra.addView(boton("▶", 26f) {
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

        // Cabecera: título + botones copiar / limpiar / cerrar
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
            Toast.makeText(activity, "Log copiado al portapapeles", Toast.LENGTH_SHORT).show()
        })
        cab.addView(botonPequeno("🗑 Limpiar") {
            LogEspejos.limpiar()
            Toast.makeText(activity, "Log limpiado", Toast.LENGTH_SHORT).show()
        })
        cab.addView(botonPequeno("✕") {
            contenedor.visibility = View.GONE
        })
        contenedor.addView(cab)

        // Cuerpo: texto scrolleable
        val texto = TextView(activity).apply {
            setTextColor(Color.parseColor("#CFCFEA"))
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(20, 10, 20, 20)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(texto)
        }
        contenedor.addView(scroll)

        // Suscripción al log
        LogEspejos.setListener {
            activity.runOnUiThread {
                texto.text = LogEspejos.textoCompleto()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        // Pintado inicial
        texto.text = LogEspejos.textoCompleto()

        padre.addView(contenedor)
        return contenedor
    }
}