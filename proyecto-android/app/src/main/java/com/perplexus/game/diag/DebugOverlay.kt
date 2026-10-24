package com.perplexus.game.diag

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** HUD técnico (FPS, estado de la bola, sensores…). Se activa con pulsación larga de 3 s en la esquina superior izquierda. */
class DebugOverlay(c: Context, private val data: DebugData) : TextView(c) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            text = data.toText()
            handler.postDelayed(this, 250L)
        }
    }

    init {
        setTextColor(Color.WHITE)
        setBackgroundColor(0x99000000.toInt())
        typeface = Typeface.MONOSPACE
        textSize = 10f
        gravity = Gravity.START
        val p = (6 * c.resources.displayMetrics.density).toInt()
        setPadding(p, p + (24 * c.resources.displayMetrics.density).toInt(), p, p)
        visibility = View.GONE
        isClickable = false
    }

    fun toggle() { if (running) hide() else show() }
    val isShown2: Boolean get() = running

    fun show() { running = true; visibility = View.VISIBLE; handler.post(tick) }
    fun hide() { running = false; visibility = View.GONE; handler.removeCallbacks(tick) }
}
