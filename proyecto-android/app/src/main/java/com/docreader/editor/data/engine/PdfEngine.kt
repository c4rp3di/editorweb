package com.docreader.editor.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PDF: la visualización la hace PdfRenderer (UI). Este engine solo aporta texto vía OCR ML Kit
 * (on-device) para indexar/buscar. No edita el PDF.
 */
class PdfEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("pdf")
    override val supportsEditing = false
    override val requiresOcr = true

    override suspend fun open(context: Context, uri: Uri, extension: String) = DocumentHandle(uri, extension, "")

    override suspend fun extractText(context: Context, handle: DocumentHandle): String =
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(handle.uri, "r") ?: return@withContext ""
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val sb = StringBuilder()
            try {
                pfd.use {
                    PdfRenderer(it).use { renderer ->
                        val pages = minOf(renderer.pageCount, MAX_OCR_PAGES)
                        for (i in 0 until pages) {
                            renderer.openPage(i).use { page ->
                                val scale = 200f / 72f
                                val w = (page.width * scale).toInt().coerceAtMost(2600)
                                val h = (w.toFloat() / page.width * page.height).toInt()
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                sb.append(recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text).append('\n')
                                bmp.recycle()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // PDF protegido, corrupto u OCR fallido: se indexa lo obtenido hasta ahora
            } finally {
                recognizer.close()
            }
            sb.toString()
        }

    override suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri) = false

    companion object { const val MAX_OCR_PAGES = 30 }
}
