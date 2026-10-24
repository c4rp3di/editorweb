package com.perplexus.game.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.perplexus.game.audio.AudioManager

/** Construcción por código de los elementos de menú (botones ≥ 48 dp, contraste ≥ 4.5:1). */
object MenuFactory {

    fun rounded(color: Int, radiusDp: Float, c: Context): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = UiConstants.dpf(c, radiusDp) }

    fun panel(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = UiConstants.dp(c, UiConstants.PANEL_PADDING_DP)
        setPadding(p, p, p, p)
        background = rounded(UiConstants.COLOR_PANEL, 20f, c)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    fun title(c: Context, text: String, color: Int = UiConstants.COLOR_TEXT): TextView = TextView(c).apply {
        this.text = text
        setTextColor(color)
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, UiConstants.dp(c, 8))
    }

    fun body(c: Context, text: String, dim: Boolean = false): TextView = TextView(c).apply {
        this.text = text
        setTextColor(if (dim) UiConstants.COLOR_TEXT_DIM else UiConstants.COLOR_TEXT)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(0, UiConstants.dp(c, 4), 0, UiConstants.dp(c, 4))
    }

    fun spacer(c: Context, dp: Int): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(1, UiConstants.dp(c, dp))
    }

    fun button(c: Context, text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text
            isAllCaps = false
            textSize = 17f
            setTextColor(UiConstants.COLOR_TEXT)
            minimumHeight = UiConstants.dp(c, UiConstants.MIN_TOUCH_DP)
            minHeight = UiConstants.dp(c, UiConstants.MIN_TOUCH_DP)
            background = rounded(if (primary) UiConstants.COLOR_ACCENT_DARK else UiConstants.COLOR_BUTTON, 14f, c)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = UiConstants.dp(c, 6)
            }
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            contentDescription = text
            setOnClickListener {
                AudioManager.playTap()
                onClick()
            }
        }

    fun progress(c: Context): ProgressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiConstants.dp(c, 12)).apply {
            topMargin = UiConstants.dp(c, 10); bottomMargin = UiConstants.dp(c, 10)
        }
    }

    fun switchRow(c: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(c).apply {
        text = label
        isChecked = checked
        textSize = 16f
        setTextColor(UiConstants.COLOR_TEXT)
        minimumHeight = UiConstants.dp(c, UiConstants.MIN_TOUCH_DP)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    /** Deslizador con etiqueta y valor. Mapea [min,max] a pasos de 1/100. */
    fun sliderRow(c: Context, label: String, min: Float, max: Float, value: Float, onChange: (Float) -> Unit): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(c).apply { setTextColor(UiConstants.COLOR_TEXT); textSize = 16f }
        fun fmt(v: Float) = "$label: %.2f".format(v)
        tv.text = fmt(value)
        val sb = SeekBar(c).apply {
            this.max = 100
            progress = (((value - min) / (max - min)) * 100f).toInt().coerceIn(0, 100)
            minimumHeight = UiConstants.dp(c, UiConstants.MIN_TOUCH_DP)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    tv.text = fmt(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(tv); row.addView(sb)
        return row
    }
}
