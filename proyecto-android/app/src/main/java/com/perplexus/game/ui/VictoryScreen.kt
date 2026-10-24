package com.perplexus.game.ui

import android.content.Context
import android.widget.LinearLayout
import com.perplexus.game.core.Session

class VictoryScreen(
    c: Context, timeMs: Long, bestMs: Long, newRecord: Boolean, hasNext: Boolean,
    onRepeat: () -> Unit, onNext: () -> Unit, onMenu: () -> Unit
) {
    val view: LinearLayout = MenuFactory.panel(c)

    init {
        view.addView(MenuFactory.title(c, "¡Nivel completado!", UiConstants.COLOR_SUCCESS))
        view.addView(MenuFactory.body(c, "Tiempo: " + Session.format(timeMs)))
        view.addView(MenuFactory.body(c, (if (newRecord) "¡Nuevo récord! " else "Mejor: ") + Session.format(bestMs), dim = !newRecord))
        view.addView(MenuFactory.spacer(c, 8))
        if (hasNext) view.addView(MenuFactory.button(c, "Siguiente nivel", primary = true) { onNext() })
        view.addView(MenuFactory.button(c, "Repetir nivel", primary = !hasNext) { onRepeat() })
        view.addView(MenuFactory.button(c, "Menú") { onMenu() })
    }
}

class DefeatScreen(c: Context, onRetry: () -> Unit, onMenu: () -> Unit) {
    val view: LinearLayout = MenuFactory.panel(c)

    init {
        view.addView(MenuFactory.title(c, "Has caído al vacío", UiConstants.COLOR_DANGER))
        view.addView(MenuFactory.body(c, "La bola salió de la pista. Inténtalo de nuevo.", dim = true))
        view.addView(MenuFactory.spacer(c, 8))
        view.addView(MenuFactory.button(c, "Reintentar", primary = true) { onRetry() })
        view.addView(MenuFactory.button(c, "Menú") { onMenu() })
    }
}
