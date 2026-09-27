package com.carpe.espejoslocos

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * UI superpuesta a la cámara: nombre del filtro arriba, slider de intensidad
 * y barra de botones abajo. Todo programático, sin tocar el layout generado.
 */
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
                    marginStart = 12
                    marginEnd = 12
                }
                setOnClickListener { onClick() }
            }
        }

        val filtros = Filtros.lista
        var idx = filtros.indexOfFirst { it.id == MotorCamara.filtroActual.id }.coerceAtLeast(0)

        fun pintar() {
            nombreFiltro.text = "${filtros[idx].icono}  ${filtros[idx].nombre}"
        }

        barra.addView(boton("◀", 28f) {
            idx = (idx - 1 + filtros.size) % filtros.size
            MotorCamara.filtroActual = filtros[idx]
            pintar()
            onCambiarFiltro(filtros[idx])
        })

        barra.addView(boton("📸", 28f) { onCapturar() })

        barra.addView(boton("🔄", 28f) { onCambiarCamara() })

        barra.addView(boton("▶", 28f) {
            idx = (idx + 1) % filtros.size
            MotorCamara.filtroActual = filtros[idx]
            pintar()
            onCambiarFiltro(filtros[idx])
        })

        root.addView(barra)
        return root
    }
}