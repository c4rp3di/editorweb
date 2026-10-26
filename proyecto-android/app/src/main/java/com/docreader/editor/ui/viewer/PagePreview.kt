package com.docreader.editor.ui.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.databinding.FragmentViewerBinding

/** Una página de papel: tamaño en puntos y cómo dibujarla (en unidades de punto). */
class PreviewPage(val wPt: Float, val hPt: Float, val draw: (Canvas) -> Unit)

class PagePreviewView(context: Context) : View(context) {
    var page: PreviewPage? = null

    init {
        setBackgroundColor(Color.WHITE)
        elevation = 8f
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val p = page
        setMeasuredDimension(w, if (p == null) 1 else (w * p.hPt / p.wPt).toInt().coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        val s = width / p.wPt
        canvas.save()
        canvas.scale(s, s)
        p.draw(canvas)
        canvas.restore()
    }
}

class PreviewAdapter(private val pages: List<PreviewPage>) : RecyclerView.Adapter<PreviewAdapter.VH>() {
    class VH(val v: PagePreviewView) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val v = PagePreviewView(parent.context)
        v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt()) }
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.v.page = pages[position]
        h.v.requestLayout()
        h.v.invalidate()
    }
}

/** Muestra una lista de páginas en la zona de vista previa con zoom (botones y pellizco). */
class PagePreviewController(
    private val ctx: Context,
    private val b: FragmentViewerBinding,
    private val onClose: () -> Unit
) {
    private var zoom = 1f
    private var pinch = 1f
    private var pages: List<PreviewPage> = emptyList()

    init {
        b.previewList.layoutManager = LinearLayoutManager(ctx)
        b.pvZoomIn.setOnClickListener { setZoom(zoom + 0.5f) }
        b.pvZoomOut.setOnClickListener { setZoom(zoom - 0.5f) }
        b.pvClose.setOnClickListener { hide() }
        val det = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { pinch *= d.scaleFactor; return true }
            override fun onScaleEnd(d: ScaleGestureDetector) { setZoom(zoom * pinch); pinch = 1f }
        })
        b.previewList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                det.onTouchEvent(e)
                return det.isInProgress
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) { det.onTouchEvent(e) }
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }

    fun show(title: String, pages: List<PreviewPage>) {
        this.pages = pages
        b.pvTitle.text = "$title · ${pages.size} pág."
        zoom = 1f
        apply()
        b.previewBar.isVisible = true
        b.pageScroll.isVisible = true
    }

    fun hide() {
        b.previewBar.isVisible = false
        b.pageScroll.isVisible = false
        b.previewList.adapter = null
        onClose()
    }

    private fun setZoom(z: Float) {
        val nz = (Math.round(z * 2f) / 2f).coerceIn(1f, 4f)
        if (nz == zoom) return
        zoom = nz
        apply()
    }

    private fun apply() {
        b.previewList.layoutParams = b.previewList.layoutParams.apply {
            width = (ctx.resources.displayMetrics.widthPixels * zoom).toInt()
        }
        b.previewList.adapter = PreviewAdapter(pages)
    }
}
