package com.carpe.gestorarchivos.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object GestorArchivos {

    fun listar(carpeta: File): List<ArchivoItem> {
        val hijos = carpeta.listFiles() ?: return emptyList()
        return hijos
            .filter { it.exists() }
            .map { ArchivoItem.desde(it) }
    }

    fun crearCarpeta(padre: File, nombre: String): File? {
        val nueva = File(padre, nombre)
        if (nueva.exists()) return null
        return if (nueva.mkdirs()) nueva else null
    }

    fun crearArchivo(padre: File, nombre: String): File? {
        val nuevo = File(padre, nombre)
        if (nuevo.exists()) return null
        return try {
            if (nuevo.createNewFile()) nuevo else null
        } catch (e: Exception) {
            null
        }
    }

    fun renombrar(archivo: File, nuevoNombre: String): Boolean {
        val destino = File(archivo.parentFile, nuevoNombre)
        if (destino.exists()) return false
        return archivo.renameTo(destino)
    }

    fun borrar(archivo: File): Boolean {
        return try {
            if (archivo.isDirectory) archivo.deleteRecursively() else archivo.delete()
        } catch (e: Exception) {
            false
        }
    }

    fun leerTexto(archivo: File): String? {
        return try {
            archivo.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun escribirTexto(archivo: File, contenido: String): Boolean {
        return try {
            archivo.writeText(contenido, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun mover(origen: File, destinoPadre: File): Boolean {
        return try {
            val destino = File(destinoPadre, origen.name)
            if (destino.exists()) return false
            if (origen.renameTo(destino)) return true
            // Si renameTo falla (distintas particiones), copiamos y borramos.
            if (origen.isDirectory) {
                if (copiarCarpetaRecursivo(origen, destinoPadre)) origen.deleteRecursively() else false
            } else {
                origen.copyTo(destino, overwrite = false)
                origen.delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun copiar(origen: File, destinoPadre: File): Boolean {
        return try {
            if (origen.isDirectory) {
                copiarCarpetaRecursivo(origen, destinoPadre)
            } else {
                val destino = File(destinoPadre, origen.name)
                origen.copyTo(destino, overwrite = false)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun copiarCarpetaRecursivo(origen: File, destinoPadre: File): Boolean {
        val nuevaCarpeta = File(destinoPadre, origen.name)
        if (!nuevaCarpeta.exists() && !nuevaCarpeta.mkdirs()) return false
        origen.listFiles()?.forEach { hijo ->
            if (hijo.isDirectory) {
                copiarCarpetaRecursivo(hijo, nuevaCarpeta)
            } else {
                try {
                    hijo.copyTo(File(nuevaCarpeta, hijo.name), overwrite = false)
                } catch (_: Exception) {}
            }
        }
        return true
    }

    fun compartir(context: Context, archivo: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                archivo
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = ArchivoItem.desde(archivo).mime ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartir con"))
        } catch (e: Exception) {
            // Fallback: intentar con file:// en apps antiguas
        }
    }
}