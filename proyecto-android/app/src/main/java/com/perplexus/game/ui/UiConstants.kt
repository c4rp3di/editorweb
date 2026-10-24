package com.perplexus.game.ui

import android.content.Context
import android.graphics.Color

object UiConstants {
    const val MIN_TOUCH_DP = 48
    const val PANEL_PADDING_DP = 22
    val COLOR_BG_SCRIM = Color.parseColor("#C005080F")
    val COLOR_PANEL = Color.parseColor("#F01A2238")
    val COLOR_ACCENT = Color.parseColor("#4FC3F7")
    val COLOR_ACCENT_DARK = Color.parseColor("#1B6C8C")
    val COLOR_BUTTON = Color.parseColor("#2A3A66")
    val COLOR_TEXT = Color.parseColor("#F2F5FA")        // contraste > 4.5:1 sobre el panel
    val COLOR_TEXT_DIM = Color.parseColor("#B8C2D6")
    val COLOR_DANGER = Color.parseColor("#FF8A8A")
    val COLOR_SUCCESS = Color.parseColor("#7BE495")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density
}
