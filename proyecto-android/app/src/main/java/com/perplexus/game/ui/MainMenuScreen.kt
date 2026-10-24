package com.perplexus.game.ui

import android.content.Context
import android.widget.LinearLayout
import com.perplexus.game.core.Session
import com.perplexus.game.levels.Level
import com.perplexus.game.storage.Prefs

/** Menú principal: selección de nivel (con bloqueo progresivo), nivel aleatorio, ajustes, log y salir. */
class MainMenuScreen(
    c: Context, levels: List<Level>, problems: List<String>,
    onLevel: (Int) -> Unit, onRandom: (Int) -> Unit, onSettings: () -> Unit, onLog: () -> Unit, onExit: () -> Unit
) {
    val view: LinearLayout = MenuFactory.panel(c)

    init {
        view.addView(MenuFactory.title(c, "Perplexus 3D"))
        view.addView(MenuFactory.body(c, "Gira el móvil para guiar la bola hasta la meta.", dim = true))
        val unlocked = Prefs.getUnlockedLevel()
        for ((i, l) in levels.withIndex()) {
            val best = Prefs.getBestTime(l.id)
            val locked = i > unlocked
            val label = (i + 1).toString() + ". " + l.name + if (locked) "  🔒" else if (best > 0L) "   " + Session.format(best) else ""
            view.addView(MenuFactory.button(c, label, primary = i == unlocked, enabled = !locked) { onLevel(i) })
        }
        if (problems.isNotEmpty()) {
            view.addView(MenuFactory.body(c, "⚠ ${problems.size} nivel(es) no válidos (ver log)", dim = true))
        }
        view.addView(MenuFactory.spacer(c, 8))
        var diff = Prefs.getRandomDifficulty()
        view.addView(MenuFactory.sliderRow(c, "Dificultad aleatoria (1-10)", 1f, 10f, diff.toFloat()) { diff = Math.round(it).coerceIn(1, 10); Prefs.setRandomDifficulty(diff) })
        view.addView(MenuFactory.button(c, "Nivel aleatorio") { onRandom(diff) })
        view.addView(MenuFactory.button(c, "Ajustes") { onSettings() })
        view.addView(MenuFactory.button(c, "Ver log") { onLog() })
        view.addView(MenuFactory.button(c, "Salir") { onExit() })
    }
}
