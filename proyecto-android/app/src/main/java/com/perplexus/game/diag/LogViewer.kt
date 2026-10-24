package com.perplexus.game.diag

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.perplexus.game.ui.MenuFactory
import com.perplexus.game.ui.UiConstants

/** Pantalla de log: últimas 500 líneas con scroll y botón volver. */
class LogViewer(c: Context, onBack: () -> Unit) {
    val view: LinearLayout = MenuFactory.panel(c)
    private val scroll = ScrollView(c)
    private val text = TextView(c)

    init {
        view.addView(MenuFactory.title(c, "Log"))
        text.apply {
            setTextColor(Color.parseColor("#DCE6F5"))
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextIsSelectable(true)
        }
        scroll.addView(text)
        view.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiConstants.dp(c, 420)))
        view.addView(MenuFactory.button(c, "Volver", primary = true) { onBack() })
        refresh()
    }

    fun refresh() {
        val lines = GameLog.lastLines(500)
        text.text = if (lines.isEmpty()) "(log vacío)" else lines.joinToString("\n")
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
