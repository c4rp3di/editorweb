package com.perplexus.game.ui

import android.content.Context
import android.widget.LinearLayout
import com.perplexus.game.storage.Prefs

/** Ajustes: sonido, vibración, sensibilidad, calidad gráfica, borrar récords. */
class SettingsScreen(
    c: Context,
    shadowsOn: Boolean, postOn: Boolean,
    onSound: (Boolean) -> Unit, onVibration: (Boolean) -> Unit, onSensitivity: (Float) -> Unit,
    onShadows: (Boolean) -> Unit, onPost: (Boolean) -> Unit,
    onClearRecords: () -> Unit, onBack: () -> Unit
) {
    val view: LinearLayout = MenuFactory.panel(c)

    init {
        view.addView(MenuFactory.title(c, "Ajustes"))
        view.addView(MenuFactory.switchRow(c, "Sonido", Prefs.getSoundEnabled()) { onSound(it) })
        view.addView(MenuFactory.switchRow(c, "Vibración", Prefs.getVibrationEnabled()) { onVibration(it) })
        view.addView(MenuFactory.spacer(c, 8))
        view.addView(MenuFactory.sliderRow(c, "Sensibilidad (gravedad)", 0.5f, 2.0f, Prefs.getSensitivity()) { onSensitivity(it) })
        view.addView(MenuFactory.spacer(c, 8))
        view.addView(MenuFactory.switchRow(c, "Sombras", shadowsOn) { onShadows(it) })
        view.addView(MenuFactory.switchRow(c, "Post-proceso (bloom, FXAA)", postOn) { onPost(it) })
        view.addView(MenuFactory.spacer(c, 8))
        view.addView(MenuFactory.button(c, "Borrar récords") { onClearRecords() })
        view.addView(MenuFactory.button(c, "Volver", primary = true) { onBack() })
    }
}
