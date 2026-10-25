package com.docreader.editor.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.docreader.editor.data.db.DocumentDao
import com.docreader.editor.data.db.DocumentEntity
import com.docreader.editor.data.engine.EngineRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: DocumentDao,
    private val registry: EngineRegistry
) {
    fun documents(query: String): Flow<List<DocumentEntity>> =
        if (query.isBlank()) dao.all() else dao.search(query.trim())

    fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment ?: "documento"
    }

    fun extensionOf(uri: Uri): String = displayName(uri).substringAfterLast('.', "").lowercase()

    private fun size(uri: Uri): Long {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && !c.isNull(i)) return c.getLong(i)
            }
        }
        return 0L
    }

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
        }
        val now = System.currentTimeMillis()
        dao.upsert(
            DocumentEntity(
                uri = uri.toString(),
                displayName = displayName(uri),
                mimeType = ctx.contentResolver.getType(uri) ?: "application/octet-stream",
                sizeBytes = size(uri),
                lastModified = now,
                extractedText = null,
                lastOpened = now
            )
        )
    }

    /** Extrae texto (OCR para PDF) y lo guarda para la búsqueda global. */
    suspend fun indexText(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val ext = extensionOf(uri)
            if (ext == "docm") return@withContext
            val engine = registry.forExtension(ext)
            val handle = engine.open(ctx, uri, ext)
            dao.updateText(uri.toString(), engine.extractText(ctx, handle))
        } catch (_: Exception) { }
    }

    suspend fun touch(uri: Uri) = dao.touch(uri.toString(), System.currentTimeMillis())
    suspend fun remove(uri: String) = dao.delete(uri)
}
