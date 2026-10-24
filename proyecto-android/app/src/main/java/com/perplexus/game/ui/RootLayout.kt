package com.perplexus.game.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.perplexus.game.diag.DebugData
import com.perplexus.game.diag.DebugOverlay

/** Contenedor de capas: GLSurfaceView + HUD + pantallas modales + overlay de depuración. */
class RootLayout(c: Context, onPause: () -> Unit, debugData: DebugData) : FrameLayout(c) {
    val glView = GLSurfaceView(c)
    val hud = HudView(c, onPause)
    val overlay = ScreenOverlay(c)
    val debugOverlay = DebugOverlay(c, debugData)

    init {
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        glView.preserveEGLContextOnPause = false
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        addView(glView, LayoutParams(mp, mp))
        addView(hud, LayoutParams(mp, mp))
        addView(debugOverlay, LayoutParams(mp, LayoutParams.WRAP_CONTENT))
        addView(overlay, LayoutParams(mp, mp))
        keepScreenOn = true
    }
}
