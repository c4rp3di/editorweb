package com.carpe.camara.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pincel = Paint().apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawLine(w / 3f, 0f, w / 3f, h, pincel)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, pincel)
        canvas.drawLine(0f, h / 3f, w, h / 3f, pincel)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, pincel)
    }
}