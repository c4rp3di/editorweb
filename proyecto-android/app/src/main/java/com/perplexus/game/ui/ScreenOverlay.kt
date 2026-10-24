package com.perplexus.game.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView

/** Contenedor de pantallas modales sobre el juego. Absorbe todos los toques mientras es visible. */
class ScreenOverlay(c: Context) : FrameLayout(c) {
    init {
        setBackgroundColor(UiConstants.COLOR_BG_SCRIM)
        isClickable = true
        isFocusable = true
        visibility = View.GONE
    }

    val showing: Boolean get() = visibility == View.VISIBLE

    fun show(content: View, scroll: Boolean = false) {
        removeAllViews()
        val m = UiConstants.dp(context, 20)
        val target: View = if (scroll) ScrollView(context).apply {
            isFillViewport = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else content
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        lp.setMargins(m, m * 2, m, m * 2)
        addView(target, lp)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
        removeAllViews()
    }
}
