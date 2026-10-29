package org.nododeverdad.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ---------- Utilidades básicas ----------

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.col(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

fun Context.toast(s: String) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

fun Context.shape(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(fill)
    g.cornerRadius = dp(radiusDp).toFloat()
    if (strokeColor != 0) g.setStroke(dp(strokeDp), strokeColor)
    return g
}

fun Context.ripple(radiusDp: Int): RippleDrawable {
    val mask = shape(Color.WHITE, radiusDp)
    return RippleDrawable(ColorStateList.valueOf(withAlpha(col(R.attr.nvText), 40)), null, mask)
}

// ---------- Texto ----------

fun Context.text(s: CharSequence, size: Float = 15f, color: Int = col(R.attr.nvText), bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = s
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * Prefs.scale(this))
    t.setTextColor(color)
    if (bold) t.typeface = Typeface.DEFAULT_BOLD
    t.setLineSpacing(0f, 1.12f)
    return t
}

fun Context.muted(s: CharSequence, size: Float = 13f): TextView = text(s, size, col(R.attr.nvMuted))

fun Context.h2(s: CharSequence): TextView = text(s, 18f, col(R.attr.nvText), true)

// ---------- Contenedores ----------

fun Context.vbox(pad: Int = 0): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    l.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    return l
}

fun Context.hbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    return l
}

fun Context.lp(
    w: Int = MATCH, h: Int = WRAP,
    top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0,
    weight: Float = 0f
): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(dp(start), dp(top), dp(end), dp(bottom))
    return p
}

fun Context.card(pad: Int = 16): LinearLayout {
    val l = vbox(pad)
    l.background = shape(col(R.attr.nvSurface), 20, col(R.attr.nvLine), 1)
    return l
}

fun Context.divider(): View {
    val v = View(this)
    v.setBackgroundColor(col(R.attr.nvLine))
    v.layoutParams = LinearLayout.LayoutParams(MATCH, 1)
    return v
}

fun Context.space(hDp: Int): View {
    val v = View(this)
    v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(hDp))
    return v
}

// ---------- Controles ----------

fun Context.button(label: String, primary: Boolean = true, onClick: () -> Unit): TextView {
    val fg = if (primary) col(R.attr.nvOnAccent) else col(R.attr.nvText)
    val t = text(label, 16f, fg, true)
    t.gravity = Gravity.CENTER
    t.minHeight = dp(52)
    t.setPadding(dp(20), dp(12), dp(20), dp(12))
    t.background = if (primary) shape(col(R.attr.nvAccent), 26)
    else shape(0, 26, col(R.attr.nvLine), 2)
    t.foreground = ripple(26)
    t.isClickable = true
    t.isFocusable = true
    t.setOnClickListener { onClick() }
    return t
}

fun Context.pill(label: String, color: Int, size: Float = 12f): TextView {
    val t = text(label, size, color, true)
    t.setPadding(dp(10), dp(4), dp(10), dp(4))
    t.background = shape(withAlpha(color, 36), 14, withAlpha(color, 110), 1)
    return t
}

fun Context.segmented(opts: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
    val row = hbox()
    row.background = shape(col(R.attr.nvSurface2), 22)
    row.setPadding(dp(4), dp(4), dp(4), dp(4))
    val views = ArrayList<TextView>()
    var sel = selected
    fun style() {
        for (i in views.indices) {
            val v = views[i]
            v.isSelected = (i == sel)
            if (i == sel) {
                v.background = shape(col(R.attr.nvAccent), 18)
                v.setTextColor(col(R.attr.nvOnAccent))
            } else {
                v.background = null
                v.setTextColor(col(R.attr.nvMuted))
            }
        }
    }
    for (i in opts.indices) {
        val t = text(opts[i], 14f, col(R.attr.nvMuted), true)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(44)
        t.setPadding(dp(8), dp(8), dp(8), dp(8))
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener {
            sel = i
            style()
            onPick(i)
        }
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        views.add(t)
    }
    style()
    return row
}

// ---------- Vistas de dibujo sencillas ----------

class BarView(ctx: Context) : View(ctx) {
    var frac: Float = 0f
        set(v) { field = v; invalidate() }
    var color: Int = Color.GRAY
        set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        track.color = context.col(R.attr.nvSurface2)
        fill.color = color
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val fw = Math.max(h, w * Math.min(1f, Math.max(0f, frac)))
        if (frac > 0f) {
            rect.set(0f, 0f, fw, h)
            canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        }
    }
}

fun Context.bar(frac: Float, color: Int, hDp: Int = 8): BarView {
    val b = BarView(this)
    b.frac = frac
    b.color = color
    b.layoutParams = LinearLayout.LayoutParams(MATCH, dp(hDp))
    return b
}

class StackBar(ctx: Context) : View(ctx) {
    private var values: List<Float> = emptyList()
    private var colors: List<Int> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(v: List<Float>, c: List<Int>) {
        values = v
        colors = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val total = values.sum()
        p.color = context.col(R.attr.nvSurface2)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, p)
        if (total <= 0f) return
        canvas.save()
        val clip = android.graphics.Path()
        clip.addRoundRect(rect, h / 2, h / 2, android.graphics.Path.Direction.CW)
        canvas.clipPath(clip)
        var x = 0f
        for (i in values.indices) {
            val seg = w * values[i] / total
            p.color = colors[i]
            rect.set(x, 0f, x + seg, h)
            canvas.drawRect(rect, p)
            x += seg
        }
        canvas.restore()
    }
}
