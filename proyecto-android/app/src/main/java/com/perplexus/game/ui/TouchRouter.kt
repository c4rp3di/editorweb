package com.perplexus.game.ui

import android.view.MotionEvent
import android.view.View

/**
 * Enruta los toques de la vista GL: si hay una pantalla modal visible no llegan al juego
 * (el overlay los absorbe antes); si no, pasan al GestureHandler.
 */
class TouchRouter(private val overlay: ScreenOverlay, private val gestures: GestureHandler) : View.OnTouchListener {
    override fun onTouch(v: View?, ev: MotionEvent): Boolean {
        if (overlay.showing) return false
        return gestures.onTouch(ev)
    }
}
