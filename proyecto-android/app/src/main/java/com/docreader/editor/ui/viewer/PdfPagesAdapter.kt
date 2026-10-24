package com.docreader.editor.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.docreader.editor.databinding.ItemPdfPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfPagesAdapter(
    private val renderer: PdfRenderer,
    private val widthPx: Int,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PdfPagesAdapter.VH>() {

    // PdfRenderer solo admite una página abierta a la vez.
    private val mutex = Mutex()

    class VH(val b: ItemPdfPageBinding) : RecyclerView.ViewHolder(b.root) { var job: Job? = null }

    override fun getItemCount() = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.job?.cancel()
        holder.b.page.setImageBitmap(null)
        holder.job = scope.launch {
            val bmp = mutex.withLock {
                withContext(Dispatchers.IO) {
                    renderer.openPage(position).use { pg ->
                        val h = (widthPx.toFloat() / pg.width * pg.height).toInt()
                        Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            holder.b.page.setImageBitmap(bmp)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
    }

    fun close() {
        runBlocking { mutex.withLock { runCatching { renderer.close() } } }
    }
}
