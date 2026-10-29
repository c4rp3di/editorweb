package org.nododeverdad.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator

/** Radar animado: cada punto es una empresa de seguimiento detectada en tu móvil. */
class RadarView(ctx: Context) : View(ctx) {

    class Blip(val angle: Float, val radius: Float, val color: Int, val size: Float)

    var blips: List<Blip> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    var active: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private var sweep = 0f
    private var anim: ValueAnimator? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: SweepGradient? = null

    init {
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = context.dp(1).toFloat()
        line.style = Paint.Style.STROKE
        line.strokeWidth = context.dp(1).toFloat()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun animationsOff(): Boolean {
        return try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animationsOff()) return
        val a = ValueAnimator.ofFloat(0f, 360f)
        a.duration = 4200
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val accent = context.col(R.attr.nvAccent)
        shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(accent, 150)),
            floatArrayOf(0f, 0.72f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = Math.min(cx, cy) - context.dp(4)
        val accent = context.col(R.attr.nvAccent)
        val lineColor = context.col(R.attr.nvLine)

        ring.color = lineColor
        for (i in 1..4) canvas.drawCircle(cx, cy, r * i / 4f, ring)
        line.color = withAlpha(lineColor, 140)
        canvas.drawLine(cx - r, cy, cx + r, cy, line)
        canvas.drawLine(cx, cy - r, cx, cy + r, line)

        // Barrido
        val sh = shader
        if (sh != null) {
            sweepPaint.shader = sh
            sweepPaint.alpha = if (active) 255 else 110
            canvas.save()
            canvas.rotate(sweep, cx, cy)
            canvas.drawCircle(cx, cy, r, sweepPaint)
            canvas.restore()
        }

        // Puntos
        for (b in blips) {
            val rad = Math.toRadians(b.angle.toDouble())
            val x = cx + (r * b.radius * Math.cos(rad)).toFloat()
            val y = cy + (r * b.radius * Math.sin(rad)).toFloat()
            val delta = ((sweep - b.angle) % 360f + 360f) % 360f
            val glow = if (delta < 140f) 1f - delta / 140f else 0f
            val alpha = (90 + 165 * glow).toInt()
            dot.color = withAlpha(b.color, alpha)
            val s = context.dp(4) * b.size
            canvas.drawCircle(x, y, s + context.dp(3) * glow, dot)
        }

        // Centro
        dot.color = accent
        canvas.drawCircle(cx, cy, context.dp(5).toFloat(), dot)
    }
}
