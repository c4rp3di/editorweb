package com.carpe.gestorarchivos.data

import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile

object GestorArchivos {

    fun listar(carpeta: DocumentFile): List<ArchivoItem> {
        val hijos = carpeta.listFiles()
        return hijos
            .filter { it.exists() }
            .map { doc ->
                ArchivoItem(
                    nombre = doc.name ?: "(sin nombre)",
                    esCarpeta = doc.isDirectory,
                    uri = doc.uri,
                    tamano = if (doc.isFile) doc.length() else 0L,
                    mime = doc.type,
                    ultimaModificacion = doc.lastModified()
                )
            }
    }

    fun crearCarpeta(padre: DocumentFile, nombre: String): DocumentFile? {
        if (padre.findFile(nombre) != null) return null
        return padre.createDirectory(nombre)
    }

    fun crearArchivo(padre: DocumentFile, nombre: String, mime: String = "text/plain"): DocumentFile? {
        if (padre.findFile(nombre) != null) return null
        return padre.createFile(mime, nombre)
    }

    fun renombrar(doc: DocumentFile, nuevoNombre: String): Boolean {
        return doc.renameTo(nuevoNombre)
    }

    fun borrar(doc: DocumentFile): Boolean = doc.delete()

    fun leerTexto(context: Context, doc: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                input.bufferedReader().readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun escribirTexto(context: Context, doc: DocumentFile, contenido: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                out.write(contenido.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun mover(context: Context, origen: DocumentFile, destinoPadre: DocumentFile): Boolean {
        return try {
            if (origen.isDirectory) {
                copiarCarpetaRecursivo(context, origen, destinoPadre)
                origen.delete()
            } else {
                val nuevo = destinoPadre.createFile(origen.type ?: "application/octet-stream", origen.name ?: "archivo")
                    ?: return false
                copiarContenido(context, origen, nuevo)
                origen.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun copiar(context: Context, origen: DocumentFile, destinoPadre: DocumentFile): Boolean {
        return try {
            if (origen.isDirectory) {
                copiarCarpetaRecursivo(context, origen, destinoPadre)
            } else {
                val nuevo = destinoPadre.createFile(origen.type ?: "application/octet-stream", origen.name ?: "archivo")
                    ?: return false
                copiarContenido(context, origen, nuevo)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copiarCarpetaRecursivo(context: Context, origen: DocumentFile, destinoPadre: DocumentFile) {
        val nombreCarpeta = origen.name ?: "carpeta"
        val nuevaCarpeta = destinoPadre.findFile(nombreCarpeta)?.takeIf { it.isDirectory }
            ?: destinoPadre.createDirectory(nombreCarpeta)
            ?: return
        origen.listFiles().forEach { hijo ->
            if (hijo.isDirectory) {
                copiarCarpetaRecursivo(context, hijo, nuevaCarpeta)
            } else {
                val nuevoHijo = nuevaCarpeta.createFile(hijo.type ?: "application/octet-stream", hijo.name ?: "archivo")
                if (nuevoHijo != null) copiarContenido(context, hijo, nuevoHijo)
            }
        }
    }

    private fun copiarContenido(context: Context, origen: DocumentFile, destino: DocumentFile) {
        context.contentResolver.openInputStream(origen.uri)?.use { input ->
            context.contentResolver.openOutputStream(destino.uri)?.use { output ->
                input.copyTo(output)
            }
        }
    }

    fun compartir(context: Context, doc: DocumentFile) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = doc.type ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, doc.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir con"))
    }
}