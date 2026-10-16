package com.imagen.chat.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.imagen.chat.ia.files.CreatedFile
import com.imagen.chat.ia.files.CreatedFilesManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreatedFilesActivity : AppCompatActivity() {
    private lateinit var manager: CreatedFilesManager
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = CreatedFilesManager(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Mis imágenes"
            textSize = 24f
            setTypeface(null, 1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "‹"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Imágenes creadas localmente"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(4, 6, 4, 10)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }

    private fun render() {
        list.removeAllViews()
        val items = manager.read().filter { it.kind == CreatedFile.Kind.IMAGE }
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No hay imágenes todavía."
                textSize = 15f
                setTextColor(Color.GRAY)
                setPadding(8, 28, 8, 28)
            })
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundColor(Color.parseColor("#14808080"))
            }
            val thumb = ImageView(this).apply {
                setImageURI(Uri.fromFile(File(item.path)))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                maxHeight = dp(220)
                contentDescription = item.prompt
            }
            card.addView(thumb, LinearLayout.LayoutParams(-1, dp(200)))
            card.addView(TextView(this).apply {
                text = item.prompt?.take(80) ?: "(sin prompt)"
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = "${item.width}x${item.height} · ${formatSize(item.sizeBytes)} · ${fmt.format(Date(item.createdAt))}"
                textSize = 11f
                setTextColor(Color.GRAY)
            })
            val acciones = LinearLayout(this).apply { gravity = Gravity.START }
            acciones.addView(Button(this).apply {
                text = "Abrir"
                setAllCaps(false)
                setOnClickListener { open(item) }
            })
            acciones.addView(Button(this).apply {
                text = "Compartir"
                setAllCaps(false)
                setOnClickListener { share(item) }
            })
            acciones.addView(Button(this).apply {
                text = "×"
                setOnClickListener {
                    manager.delete(item)
                    render()
                }
            })
            card.addView(acciones)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 6, 0, 6) })
        }
    }

    private fun open(item: CreatedFile) {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(item), item.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun share(item: CreatedFile) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uriFor(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartir imagen"))
    }

    private fun uriFor(item: CreatedFile): Uri =
        FileProvider.getUriForFile(this, "${packageName}.fileprovider", File(item.path))

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
