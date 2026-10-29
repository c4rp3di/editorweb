package org.nododeverdad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Grafo en tres anillos: tus apps (centro) → empresas que reciben datos → país donde está su sede.
 * Se puede mover con un dedo, ampliar con pellizco y tocar un nodo para ver su ficha.
 */
class GraphView(ctx: Context) : View(ctx) {

    class GNode(val key: String, val kind: Int, val label: String, val color: Int, val weight: Float) {
        var x = 0f
        var y = 0f
        var ang = 0f
    }

    class GEdge(val a: String, val b: String, val w: Float, val dashed: Boolean)

    var onSelect: ((GNode) -> Unit)? = null

    private var nodes: List<GNode> = emptyList()
    private var edges: List<GEdge> = emptyList()
    private val byKey = HashMap<String, GNode>()
    var selectedKey: String? = null
        set(v) {
            field = v
            invalidate()
        }

    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pNode = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale = Math.max(0.6f, Math.min(3.2f, scale * detector.scaleFactor))
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            pick(e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX
            panY -= distanceY
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            scale = 1f
            panX = 0f
            panY = 0f
            invalidate()
            return true
        }
    })

    init {
        pLine.style = Paint.Style.STROKE
        pRing.style = Paint.Style.STROKE
        pText.textAlign = Paint.Align.CENTER
    }

    fun setGraph(n: List<GNode>, e: List<GEdge>) {
        nodes = n
        edges = e
        byKey.clear()
        for (x in n) byKey[x.key] = x
        layout()
        scale = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun layout() {
        val ring = floatArrayOf(0.30f, 0.62f, 0.92f)
        val kinds = arrayOf(ArrayList<GNode>(), ArrayList<GNode>(), ArrayList<GNode>())
        for (n in nodes) if (n.kind in 0..2) kinds[n.kind].add(n)

        // Anillo 0: apps repartidas por igual
        val apps = kinds[0]
        for (i in apps.indices) {
            apps[i].ang = (-Math.PI / 2 + 2 * Math.PI * i / Math.max(1, apps.size)).toFloat()
        }
        // Anillos 1 y 2: se ordenan por el ángulo medio de sus vecinos del anillo anterior
        for (k in 1..2) {
            val list = kinds[k]
            for (n in list) {
                var sx = 0.0
                var sy = 0.0
                var cnt = 0
                for (e in edges) {
                    val other = if (e.a == n.key) byKey[e.b] else if (e.b == n.key) byKey[e.a] else null
                    if (other != null && other.kind == k - 1) {
                        sx += Math.cos(other.ang.toDouble()) * e.w
                        sy += Math.sin(other.ang.toDouble()) * e.w
                        cnt++
                    }
                }
                n.ang = if (cnt == 0) 0f else Math.atan2(sy, sx).toFloat()
            }
            list.sortBy { it.ang }
            for (i in list.indices) {
                list[i].ang = (-Math.PI + 2 * Math.PI * (i + 0.5) / list.size).toFloat()
            }
        }
        for (k in 0..2) {
            for (n in kinds[k]) {
                n.x = (ring[k] * Math.cos(n.ang.toDouble())).toFloat()
                n.y = (ring[k] * Math.sin(n.ang.toDouble())).toFloat()
            }
        }
    }

    private fun unit(): Float = Math.min(width, height) / 2f * 0.84f

    private fun sx(x: Float): Float = width / 2f + panX + x * unit() * scale
    private fun sy(y: Float): Float = height / 2f + panY + y * unit() * scale

    private fun radiusOf(n: GNode): Float {
        val base = when (n.kind) {
            0 -> 13f
            1 -> 8f
            else -> 10f
        }
        return context.dp(1) * (base + 5f * n.weight) * Math.min(1.6f, Math.max(0.8f, scale))
    }

    private fun pick(x: Float, y: Float) {
        var best: GNode? = null
        var bestD = Float.MAX_VALUE
        val minHit = context.dp(26).toFloat()
        for (n in nodes) {
            val dx = sx(n.x) - x
            val dy = sy(n.y) - y
            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (d <= Math.max(minHit, radiusOf(n)) && d < bestD) {
                best = n
                bestD = d
            }
        }
        if (best != null) {
            selectedKey = best.key
            onSelect?.invoke(best)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val lineColor = context.col(R.attr.nvLine)
        val textColor = context.col(R.attr.nvText)
        val muted = context.col(R.attr.nvMuted)
        val cx = sx(0f)
        val cy = sy(0f)
        val u = unit() * scale

        pRing.color = withAlpha(lineColor, 150)
        pRing.strokeWidth = context.dp(1).toFloat()
        canvas.drawCircle(cx, cy, u * 0.30f, pRing)
        canvas.drawCircle(cx, cy, u * 0.62f, pRing)
        canvas.drawCircle(cx, cy, u * 0.92f, pRing)

        val sel = selectedKey
        // Aristas
        for (e in edges) {
            val a = byKey[e.a] ?: continue
            val b = byKey[e.b] ?: continue
            val hot = sel != null && (e.a == sel || e.b == sel)
            pLine.color = if (hot) context.col(R.attr.nvAccent) else withAlpha(muted, if (sel == null) 90 else 40)
            pLine.strokeWidth = context.dp(1).toFloat() * (if (hot) 2.4f else 1f + 1.2f * e.w)
            if (e.dashed) {
                pLine.pathEffect = android.graphics.DashPathEffect(floatArrayOf(context.dp(5).toFloat(), context.dp(4).toFloat()), 0f)
            } else {
                pLine.pathEffect = null
            }
            canvas.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), pLine)
        }
        pLine.pathEffect = null

        // Centro («tu móvil»)
        pNode.color = context.col(R.attr.nvAccent)
        canvas.drawCircle(cx, cy, context.dp(11).toFloat(), pNode)
        pText.textSize = context.dp(11).toFloat() * Prefs.scale(context)
        pText.color = textColor
        canvas.drawText("Tu móvil", cx, cy + context.dp(26), pText)

        // Nodos
        val showAllLabels = nodes.size <= 26 || scale > 1.5f
        for (n in nodes) {
            val x = sx(n.x)
            val y = sy(n.y)
            val rr = radiusOf(n)
            val dim = sel != null && sel != n.key && !isNeighbor(sel, n.key)
            pNode.color = withAlpha(n.color, if (dim) 90 else 255)
            canvas.drawCircle(x, y, rr, pNode)
            if (n.key == sel) {
                pRing.color = textColor
                pRing.strokeWidth = context.dp(2).toFloat()
                canvas.drawCircle(x, y, rr + context.dp(4), pRing)
            }
            val label = n.kind == 0 || n.kind == 2 || showAllLabels || n.key == sel
            if (label && !dim) {
                pText.color = textColor
                pText.textSize = context.dp(if (n.kind == 1) 10 else 11).toFloat() * Prefs.scale(context)
                val t = if (n.label.length > 16) n.label.substring(0, 15) + "…" else n.label
                canvas.drawText(t, x, y + rr + context.dp(13), pText)
            }
        }
    }

    private fun isNeighbor(a: String, b: String): Boolean {
        for (e in edges) if ((e.a == a && e.b == b) || (e.a == b && e.b == a)) return true
        return false
    }
}
