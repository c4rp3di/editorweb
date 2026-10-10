package es.metromadrid.probe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::output.isInitialized) refresh()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(0xFFF6F7F9.toInt())
        }
        val title = TextView(this).apply {
            text = "Sonda local de accesibilidad"
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xFF152238.toInt())
        }
        outer.addView(title, LinearLayout.LayoutParams(-1, -2))

        val explanation = TextView(this).apply {
            text = "Solo lee el árbol accesible de es.metromadrid.metroandroid. No hace capturas de imagen, no guarda archivos y no tiene permiso de Internet. Solo lee la pantalla mientras el servicio de Accesibilidad está activado manualmente en Android."
            textSize = 14f
            setTextColor(0xFF354052.toInt())
            setPadding(0, dp(8), 0, dp(12))
        }
        outer.addView(explanation, LinearLayout.LayoutParams(-1, -2))

        val openSettings = Button(this).apply {
            text = "Abrir Ajustes de accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        outer.addView(openSettings, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val reload = Button(this).apply {
            text = "Actualizar lectura"
            setOnClickListener { refresh() }
        }
        row.addView(reload, LinearLayout.LayoutParams(0, -2, 1f))
        val copy = Button(this).apply {
            text = "Copiar texto"
            setOnClickListener { copyOutput() }
        }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        outer.addView(row, LinearLayout.LayoutParams(-1, -2))

        val clear = Button(this).apply {
            text = "Borrar lectura de memoria"
            setOnClickListener { ProbeState.clear(); refresh() }
        }
        outer.addView(clear, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF526071.toInt())
            setPadding(0, dp(6), 0, dp(6))
        }
        outer.addView(status, LinearLayout.LayoutParams(-1, -2))

        output = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF1A2433.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(output, ViewGroup.LayoutParams(-1, -2))
        }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer, ViewGroup.LayoutParams(-1, -1))
    }

    private fun refresh() {
        output.text = ProbeState.read()
        val at = ProbeState.time()
        status.text = if (at == 0L) "Estado: esperando una pantalla de Metro."
        else "Última lectura: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
    }

    private fun copyOutput() {
        val data = ProbeState.read()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Metro Accessibility Probe", data))
            Toast.makeText(this, "Texto copiado. Revísalo antes de compartirlo.", Toast.LENGTH_LONG).show()
        }
    }
}
