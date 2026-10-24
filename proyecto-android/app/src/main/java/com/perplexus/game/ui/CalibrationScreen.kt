package com.perplexus.game.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Pantalla de calibración: instrucciones, barra de progreso y botón "Calibrar y jugar". */
class CalibrationScreen(c: Context, private val onCalibrate: () -> Unit, private val onBack: () -> Unit) {
    val view: LinearLayout = MenuFactory.panel(c)
    private val progress: ProgressBar = MenuFactory.progress(c)
    private val status: TextView = MenuFactory.body(c, "", dim = true)
    private val start: Button

    init {
        view.addView(MenuFactory.title(c, "Calibración"))
        view.addView(MenuFactory.body(c, "Sostén el móvil en la posición en la que vas a jugar y mantenlo quieto durante 1 s. Después pulsa Calibrar."))
        view.addView(progress)
        view.addView(status)
        start = MenuFactory.button(c, "Calibrar y jugar", primary = true) { onCalibrate() }
        view.addView(start)
        view.addView(MenuFactory.button(c, "Volver al menú") { onBack() })
    }

    fun setProgress(f: Float) { progress.progress = (f * 1000f).toInt().coerceIn(0, 1000) }
    fun setMessage(m: String) { status.text = m }
    fun setBusy(b: Boolean) { start.isEnabled = !b; start.alpha = if (b) 0.45f else 1f }
    fun setProblem(p: Boolean) {
        status.setTextColor(if (p) UiConstants.COLOR_DANGER else UiConstants.COLOR_TEXT_DIM)
    }
    @Suppress("unused") fun asView(): View = view
}
