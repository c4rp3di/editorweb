package com.perplexus.game.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Gestos sobre la vista de juego: dos dedos arrastrando = órbita, pinza = zoom, doble toque = recentrar,
 * pulsación larga de 3 s en la esquina superior izquierda = overlay de depuración.
 * Un solo dedo no hace nada (el control es por inclinación).
 */
class GestureHandler(private val c: Context, private val listener: Listener) {
    interface Listener {
        fun onOrbit(dYawRad: Float, dPitchRad: Float)
        fun onZoom(factor: Float)
        fun onRecenter()
        fun onToggleDebug()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val cornerPx = UiConstants.dp(c, 96)
    private var downX = 0f; private var downY = 0f
    private var longPressPosted = false
    private val longPress = Runnable { longPressPosted = false; listener.onToggleDebug() }

    private var lastCx = 0f; private var lastCy = 0f; private var lastDist = 0f
    private var twoFinger = false

    private val detector = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean { listener.onRecenter(); return true }
    })

    fun onTouch(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                if (ev.x < cornerPx && ev.y < cornerPx) { handler.postDelayed(longPress, 3000L); longPressPosted = true }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelLongPress()
                if (ev.pointerCount == 2) startTwoFinger(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressPosted && hypot(ev.x - downX, ev.y - downY) > slop * 2) cancelLongPress()
                if (twoFinger && ev.pointerCount >= 2) {
                    val cx = (ev.getX(0) + ev.getX(1)) * 0.5f
                    val cy = (ev.getY(0) + ev.getY(1)) * 0.5f
                    val dist = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
                    listener.onOrbit(-(cx - lastCx) * 0.006f, (cy - lastCy) * 0.006f)
                    if (lastDist > 1f && dist > 1f) listener.onZoom(lastDist / dist)
                    lastCx = cx; lastCy = cy; lastDist = dist
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { if (ev.pointerCount <= 2) twoFinger = false }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { cancelLongPress(); twoFinger = false }
        }
        return true
    }

    private fun startTwoFinger(ev: MotionEvent) {
        twoFinger = true
        lastCx = (ev.getX(0) + ev.getX(1)) * 0.5f
        lastCy = (ev.getY(0) + ev.getY(1)) * 0.5f
        lastDist = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
    }

    private fun cancelLongPress() {
        if (longPressPosted) { handler.removeCallbacks(longPress); longPressPosted = false }
    }
}
