package com.carpe.gestorarchivos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/** ImageView con zoom por pellizco, arrastre y doble toque. */
class ZoomImageView @JvmOverloads constructor(
    contexto: Context,
    atributos: AttributeSet? = null
) : AppCompatImageView(contexto, atributos) {

    private val m = Matrix()
    private val valores = FloatArray(9)
    private var escalaMin = 1f
    private var escalaMax = 8f

    private val detectorEscala = ScaleGestureDetector(contexto, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val actual = escalaActual()
            val nueva = (actual * d.scaleFactor).coerceIn(escalaMin, escalaMax)
            val f = nueva / actual
            m.postScale(f, f, d.focusX, d.focusY)
            corregir()
            imageMatrix = m
            return true
        }
    })

    private val detectorGestos = GestureDetector(contexto, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy)
            corregir()
            imageMatrix = m
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val actual = escalaActual()
            val objetivo = if (actual > escalaMin * 1.5f) escalaMin else escalaMin * 3f
            val f = objetivo / actual
            m.postScale(f, f, e.x, e.y)
            corregir()
            imageMatrix = m
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    private fun escalaActual(): Float {
        m.getValues(valores)
        return valores[Matrix.MSCALE_X]
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        ajustarInicial()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ajustarInicial()
    }

    private fun ajustarInicial() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val s = minOf(width / dw, height / dh)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        escalaMin = s
        escalaMax = s * 8f
        imageMatrix = m
    }

    private fun corregir() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detectorEscala.onTouchEvent(e)
        if (!detectorEscala.isInProgress) detectorGestos.onTouchEvent(e)
        return true
    }
}
