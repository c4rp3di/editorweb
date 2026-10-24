package com.perplexus.game.ui

import android.content.Context
import android.widget.LinearLayout

class PauseScreen(
    c: Context,
    onResume: () -> Unit, onRestart: () -> Unit, onRecalibrate: () -> Unit,
    onSettings: () -> Unit, onLog: () -> Unit, onMenu: () -> Unit, onExit: () -> Unit
) {
    val view: LinearLayout = MenuFactory.panel(c)

    init {
        view.addView(MenuFactory.title(c, "Pausa"))
        view.addView(MenuFactory.button(c, "Reanudar", primary = true) { onResume() })
        view.addView(MenuFactory.button(c, "Reiniciar nivel") { onRestart() })
        view.addView(MenuFactory.button(c, "Recalibrar") { onRecalibrate() })
        view.addView(MenuFactory.button(c, "Ajustes") { onSettings() })
        view.addView(MenuFactory.button(c, "Ver log") { onLog() })
        view.addView(MenuFactory.button(c, "Menú de niveles") { onMenu() })
        view.addView(MenuFactory.button(c, "Salir") { onExit() })
    }
}
