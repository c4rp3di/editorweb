package com.carpe.gestorarchivos.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** Convierte los Uri que llegan de otras apps en rutas reales o, si no hay, en copias en la caché. */
object ResolvedorUri {

    private const val CARPETA_CACHE = "entrantes"

    /** Ruta de un content:// del almacenamiento del sistema (externalstorage), o null. */
    fun rutaExterna(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = try {
            if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri)
            else DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val partes = id.split(":", limit = 2)
        val raiz = if (partes[0] == "primary") Environment.getExternalStorageDirectory().path
        else "/storage/${partes[0]}"
        return if (partes.size > 1 && partes[1].isNotEmpty()) "$raiz/${partes[1]}" else raiz
    }

    /** Intenta obtener el archivo real al que apunta [uri]. Devuelve null si solo se puede leer como flujo. */
    fun aArchivo(ctx: Context, uri: Uri): File? {
        val candidato: String? = when (uri.scheme) {
            "file" -> uri.path
            "content" -> rutaDeContent(ctx, uri)
            else -> null
        }
        if (candidato == null) return null
        val f = File(candidato)
        return if (f.isFile && f.canRead() && dentroDeAlmacenamiento(ctx, f)) f else null
    }

    private fun rutaDeContent(ctx: Context, uri: Uri): String? {
        // Nuestro propio proveedor: el id del documento es la ruta
        if (uri.authority == ProveedorDocumentos.autoridad(ctx)) {
            return try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) { null }
        }
        rutaExterna(uri)?.let { return it }
        // Descargas del sistema: «raw:/storage/…»
        if (uri.authority == "com.android.providers.downloads.documents") {
            val id = try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) { null }
            if (id != null && id.startsWith("raw:")) return id.removePrefix("raw:")
        }
        // Resto (MediaStore, etc.): columna _data si el proveedor la ofrece
        return try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun dentroDeAlmacenamiento(ctx: Context, f: File): Boolean {
        val ruta = try { f.canonicalPath } catch (_: Exception) { return false }
        return Almacenamientos.volumenes(ctx).any { ruta.startsWith(it.raiz.absolutePath + File.separator) }
    }

    /** Nombre «humano» del contenido: DISPLAY_NAME, o el último tramo de la ruta (con extensión según el MIME si falta). */
    fun nombreDe(ctx: Context, uri: Uri, mime: String? = null): String {
        var nombre: String? = null
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) nombre = c.getString(0)
                }
            } catch (_: Exception) { }
        }
        if (nombre.isNullOrBlank()) nombre = uri.lastPathSegment?.substringAfterLast('/')
        var limpio = (nombre ?: "archivo").replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_").trim()
        if (limpio.isEmpty() || limpio == "." || limpio == "..") limpio = "archivo"
        if (!limpio.contains('.')) {
            val tipo = mime ?: try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
            val ext = tipo?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (!ext.isNullOrEmpty()) limpio += ".$ext"
        }
        return limpio
    }

    /** Copia el contenido de [uri] en la caché de la app. Hay que llamarlo fuera del hilo principal. */
    fun copiarACache(ctx: Context, uri: Uri, nombre: String): File? {
        return try {
            val dir = File(File(ctx.cacheDir, CARPETA_CACHE), System.currentTimeMillis().toString())
            if (!dir.mkdirs()) return null
            val destino = File(dir, nombre)
            ctx.contentResolver.openInputStream(uri)?.use { entrada ->
                destino.outputStream().use { salida -> entrada.copyTo(salida) }
            } ?: return null
            destino
        } catch (_: Exception) {
            null
        }
    }

    fun esTemporal(ctx: Context, f: File): Boolean =
        f.absolutePath.startsWith(File(ctx.cacheDir, CARPETA_CACHE).absolutePath + File.separator)

    /** Borra las copias de más de un día. */
    fun purgarCache(ctx: Context) {
        val base = File(ctx.cacheDir, CARPETA_CACHE)
        val limite = System.currentTimeMillis() - 86_400_000L
        for (d in base.listFiles() ?: return) if (d.lastModified() < limite) d.deleteRecursively()
    }
}
