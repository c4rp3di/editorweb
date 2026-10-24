package com.docreader.editor.data.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import javax.inject.Inject

/** HTML: se muestra el texto limpio; al guardar se escribe texto plano (se pierden las etiquetas). */
class HtmlEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("html", "htm")
    override val supportsEditing = true
    override val requiresOcr = false

    override suspend fun open(context: Context, uri: Uri, extension: String) = withContext(Dispatchers.IO) {
        val html = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw java.io.IOException("No se pudo abrir el archivo")
        DocumentHandle(uri, extension, Jsoup.parse(html).body().wholeText().trim())
    }

    override suspend fun extractText(context: Context, handle: DocumentHandle) = handle.text

    override suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(outputUri, "wt")?.use {
                    it.write(handle.text.toByteArray(Charsets.UTF_8))
                } != null
            } catch (e: Exception) {
                false
            }
        }
}
