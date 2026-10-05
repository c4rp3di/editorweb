package com.ejemplo.chat.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.ejemplo.chat.R
import com.ejemplo.chat.ia.files.CreatedFile
import com.ejemplo.chat.ia.files.CreatedFilesManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreatedFilesActivity : AppCompatActivity() {
    private lateinit var manager: CreatedFilesManager
    private lateinit var list: LinearLayout
    private var filter = Filter.ALL

    private enum class Filter { ALL, IMAGE, VIDEO, OTHER }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = CreatedFilesManager(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Mis archivos"; textSize = 24f; setTypeface(null, 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "‹"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(header)

        val filters = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf("Todos" to Filter.ALL, "Imágenes" to Filter.IMAGE, "Vídeos" to Filter.VIDEO, "Otros" to Filter.OTHER).forEach { (label, value) ->
            filters.addView(Button(this).apply { text = label; setAllCaps(false); setOnClickListener { filter = value; render() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        root.addView(filters)
        root.addView(TextView(this).apply { text = "Archivos creados localmente por Chat Pro"; textSize = 12f; setTextColor(Color.GRAY); setPadding(4, 6, 4, 10) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) render() }

    private fun render() {
        list.removeAllViews()
        val items = manager.read().filter { filter == Filter.ALL || it.kind.name == filter.name }
        if (items.isEmpty()) {
            list.addView(TextView(this).apply { text = "No hay archivos todavía."; textSize = 15f; setTextColor(Color.GRAY); setPadding(8, 28, 8, 28) })
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
        items.forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 8, 4, 8) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(this).apply { text = File(item.path).name; textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val detail = TextView(this).apply { text = "${label(item.kind)} · ${formatSize(item.sizeBytes)} · ${fmt.format(Date(item.createdAt))}"; textSize = 11f; setTextColor(Color.GRAY) }
            info.addView(name); info.addView(detail)
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = "Abrir"; setAllCaps(false); setOnClickListener { open(item) } })
            row.addView(Button(this).apply { text = "Compartir"; setAllCaps(false); setOnClickListener { share(item) } })
            row.addView(Button(this).apply { text = "×"; setOnClickListener { manager.delete(item); render() } })
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(0x22000000); layoutParams = LinearLayout.LayoutParams(-1, 1) })
        }
    }

    private fun open(item: CreatedFile) {
        if (item.kind == CreatedFile.Kind.VIDEO) {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(item), item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } else {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(item), item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private fun share(item: CreatedFile) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType; putExtra(Intent.EXTRA_STREAM, uriFor(item)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartir archivo"))
    }

    private fun uriFor(item: CreatedFile): Uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", File(item.path))
    private fun label(kind: CreatedFile.Kind) = when (kind) { CreatedFile.Kind.IMAGE -> "🖼 Imagen"; CreatedFile.Kind.VIDEO -> "🎬 Vídeo"; CreatedFile.Kind.OTHER -> "📄 Archivo" }
    private fun formatSize(bytes: Long): String = when { bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024); bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024); bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0); else -> "$bytes B" }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
