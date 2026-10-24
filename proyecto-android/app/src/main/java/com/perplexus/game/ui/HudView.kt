package com.perplexus.game.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.perplexus.game.core.Session

/** HUD: nombre del nivel (arriba izq.), mejor tiempo (arriba centro), tiempo (arriba der.), botón de pausa (abajo izq.). */
class HudView(c: Context, onPause: () -> Unit) : FrameLayout(c) {
    private val levelText = label(c, Gravity.START or Gravity.TOP)
    private val bestText = label(c, Gravity.CENTER_HORIZONTAL or Gravity.TOP)
    private val timeText = label(c, Gravity.END or Gravity.TOP)
    private val pauseBtn: Button

    init {
        val m = UiConstants.dp(c, 12)
        val top = UiConstants.dp(c, 28)
        for (t in listOf(levelText, bestText, timeText)) {
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, t.tag as Int)
            lp.setMargins(m, top, m, 0)
            addView(t, lp)
        }
        timeText.textSize = 20f
        pauseBtn = Button(c).apply {
            text = "II"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiConstants.COLOR_TEXT)
            background = MenuFactory.rounded(UiConstants.COLOR_BUTTON, 28f, c)
            minimumWidth = UiConstants.dp(c, 56); minimumHeight = UiConstants.dp(c, 56)
            contentDescription = "Pausa"
            setOnClickListener { onPause() }
        }
        val blp = LayoutParams(UiConstants.dp(c, 56), UiConstants.dp(c, 56), Gravity.START or Gravity.BOTTOM)
        blp.setMargins(m, 0, 0, UiConstants.dp(c, 28))
        addView(pauseBtn, blp)
        setLevelName("")
        setBest(0)
        setTime(0)
        isClickable = false
    }

    private fun label(c: Context, g: Int): TextView = TextView(c).apply {
        setTextColor(UiConstants.COLOR_TEXT)
        setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
        textSize = 15f
        tag = g
    }

    fun setLevelName(n: String) { levelText.text = n }
    fun setBest(ms: Long) { bestText.text = "Mejor " + Session.format(ms) }
    fun setTime(ms: Long) { timeText.text = Session.format(ms) }
    fun setPauseVisible(v: Boolean) { pauseBtn.visibility = if (v) View.VISIBLE else View.INVISIBLE }
}
