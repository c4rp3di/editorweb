package com.carpe.gestorarchivos.data

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.carpe.gestorarchivos.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Expone el almacenamiento (interno y tarjetas) en el selector de archivos del sistema (SAF).
 * Gracias a esto la app aparece en el menú lateral de «Guardar como», «Abrir», «Elegir carpeta»…
 * de cualquier otra app. Solo el sistema (permiso MANAGE_DOCUMENTS) puede hablar con él;
 * las demás apps reciben acceso únicamente a lo que el usuario elige.
 *
 * El id de cada documento es su ruta absoluta.
 */
class ProveedorDocumentos : DocumentsProvider() {

    companion object {
        private val COLUMNAS_RAIZ = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES
        )
        private val COLUMNAS_DOC = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )

        fun autoridad(ctx: Context): String = ctx.packageName + ".documents"

        fun uriDocumento(ctx: Context, f: File): Uri =
            DocumentsContract.buildDocumentUri(autoridad(ctx), f.absolutePath)

        fun uriArbol(ctx: Context, f: File): Uri =
            DocumentsContract.buildTreeDocumentUri(autoridad(ctx), f.absolutePath)

        /** Avisa al sistema de que las raíces cambiaron (p. ej. al conceder el permiso de archivos). */
        fun avisarCambioRaices(ctx: Context) {
            try {
                ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(autoridad(ctx)), null)
            } catch (_: Exception) { }
        }
    }

    override fun onCreate(): Boolean = true

    private fun contexto(): Context = context ?: throw FileNotFoundException("Sin contexto")

    // ------------------------------------------------------------------ raíces

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: COLUMNAS_RAIZ)
        val ctx = context ?: return c
        val tienePermiso = Permisos.tiene(ctx)
        for (v in Almacenamientos.volumenes(ctx)) {
            val fila = c.newRow()
            fila.add(Root.COLUMN_ROOT_ID, v.raiz.absolutePath)
            fila.add(Root.COLUMN_DOCUMENT_ID, v.raiz.absolutePath)
            fila.add(Root.COLUMN_TITLE, "Gestor · " + Almacenamientos.nombreCorto(ctx, v.raiz))
            fila.add(
                Root.COLUMN_SUMMARY,
                if (tienePermiso) espacioLibre(v.raiz) else "Concede el permiso de archivos en la app"
            )
            fila.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            fila.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            fila.add(Root.COLUMN_AVAILABLE_BYTES, bytesLibres(v.raiz))
        }
        return c
    }

    private fun bytesLibres(raiz: File): Long = try { StatFs(raiz.absolutePath).availableBytes } catch (_: Exception) { 0L }

    private fun espacioLibre(raiz: File): String = Formato.tamano(bytesLibres(raiz)) + " libres"

    // ------------------------------------------------------------------ documentos

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: COLUMNAS_DOC)
        incluir(c, archivoDe(documentId))
        return c
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
    ): Cursor {
        val c = MatrixCursor(projection ?: COLUMNAS_DOC)
        val ctx = contexto()
        val padre = archivoDe(parentDocumentId)
        if (!padre.isDirectory) throw FileNotFoundException("No es una carpeta")
        c.setNotificationUri(
            ctx.contentResolver,
            DocumentsContract.buildChildDocumentsUri(autoridad(ctx), padre.absolutePath)
        )
        for (hijo in padre.listFiles() ?: emptyArray()) {
            if (hijo.name == Papelera.NOMBRE) continue
            incluir(c, hijo)
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val padre = File(parentDocumentId).canonicalPath
            val hijo = File(documentId).canonicalPath
            hijo == padre || hijo.startsWith(padre + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    override fun getDocumentType(documentId: String): String = mimeDe(archivoDe(documentId))

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = archivoDe(documentId)
        if (f.isDirectory) throw FileNotFoundException("Es una carpeta")
        val modo = ParcelFileDescriptor.parseMode(mode)
        val escribe = mode.contains('w') || mode.contains('a')
        if (!escribe && !f.exists()) throw FileNotFoundException("No existe")
        return ParcelFileDescriptor.open(f, modo)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val ctx = contexto()
        val padre = archivoDe(parentDocumentId)
        if (!padre.isDirectory || !padre.canWrite()) throw FileNotFoundException("No se puede escribir en esa carpeta")
        var nombre = displayName.replace(Regex("[\\\\/\\u0000]"), "_").trim().ifEmpty { "archivo" }
        val esCarpeta = mimeType == Document.MIME_TYPE_DIR
        if (!esCarpeta && !nombre.contains('.') && mimeType != "application/octet-stream") {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (!ext.isNullOrEmpty()) nombre += ".$ext"
        }
        val destino = GestorArchivos.nombreLibre(padre, nombre)
        val creado = try {
            if (esCarpeta) destino.mkdirs() else destino.createNewFile()
        } catch (_: Exception) {
            false
        }
        if (!creado) throw FileNotFoundException("No se pudo crear")
        avisarHijos(ctx, padre)
        return destino.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val ctx = contexto()
        val f = archivoDe(documentId)
        if (Almacenamientos.esRaiz(ctx, f)) throw FileNotFoundException("No se puede borrar el almacenamiento")
        val padre = f.parentFile
        // Va a la papelera de la app (recuperable); si ya estaba en ella se borra de verdad
        if (!Papelera(ctx).enviar(f)) throw FileNotFoundException("No se pudo borrar")
        if (padre != null) avisarHijos(ctx, padre)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val ctx = contexto()
        val f = archivoDe(documentId)
        if (Almacenamientos.esRaiz(ctx, f)) throw FileNotFoundException("No se puede renombrar el almacenamiento")
        val nuevo = displayName.replace(Regex("[\\\\/\\u0000]"), "_").trim()
        if (nuevo.isEmpty()) throw FileNotFoundException("Nombre no válido")
        if (nuevo == f.name) return null
        if (!GestorArchivos.renombrar(f, nuevo)) throw FileNotFoundException("No se pudo renombrar")
        val destino = File(f.parentFile, nuevo)
        RepositorioFavoritos(ctx).actualizarRuta(f.absolutePath, destino.absolutePath)
        f.parentFile?.let { avisarHijos(ctx, it) }
        return destino.absolutePath
    }

    // ------------------------------------------------------------------ utilidades

    /** Comprueba permiso y que la ruta esté dentro de algún almacenamiento (evita salir con «../»). */
    private fun archivoDe(id: String): File {
        val ctx = contexto()
        if (!Permisos.tiene(ctx)) {
            throw FileNotFoundException("Concede a Gestor de Archivos el permiso de acceso a todos los archivos")
        }
        val f = File(id)
        val canonica = try { f.canonicalPath } catch (_: Exception) { throw FileNotFoundException("Ruta no válida") }
        val dentro = Almacenamientos.volumenes(ctx).any { v ->
            val raiz = try { v.raiz.canonicalPath } catch (_: Exception) { v.raiz.absolutePath }
            canonica == raiz || canonica.startsWith(raiz + File.separator)
        }
        if (!dentro) throw FileNotFoundException("Fuera de los almacenamientos")
        return f
    }

    private fun mimeDe(f: File): String {
        if (f.isDirectory) return Document.MIME_TYPE_DIR
        val m = ArchivoItem.adivinarMime(f.name)
        if (m != "*/*" && !m.endsWith("/*")) return m
        val ext = f.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun incluir(c: MatrixCursor, f: File) {
        val ctx = contexto()
        val esRaiz = Almacenamientos.esRaiz(ctx, f)
        var flags = 0
        if (f.isDirectory) {
            if (f.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (f.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (!esRaiz && f.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        val fila = c.newRow()
        fila.add(Document.COLUMN_DOCUMENT_ID, f.absolutePath)
        fila.add(
            Document.COLUMN_DISPLAY_NAME,
            if (esRaiz) "Gestor · " + Almacenamientos.nombreCorto(ctx, f) else f.name
        )
        fila.add(Document.COLUMN_SIZE, if (f.isDirectory) 0L else f.length())
        fila.add(Document.COLUMN_MIME_TYPE, mimeDe(f))
        fila.add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
        fila.add(Document.COLUMN_FLAGS, flags)
    }

    private fun avisarHijos(ctx: Context, padre: File) {
        try {
            ctx.contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(autoridad(ctx), padre.absolutePath), null
            )
        } catch (_: Exception) { }
    }
}
