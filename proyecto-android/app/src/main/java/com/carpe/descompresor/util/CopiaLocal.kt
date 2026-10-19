package com.carpe.descompresor.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object CopiaLocal {
    class Resultado(val archivo: File, val nombre: String)

    fun carpetaAbiertos(ctx: Context): File {
        val d = File(ctx.cacheDir, "abiertos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun limpiarAbiertos(ctx: Context) {
        carpetaAbiertos(ctx).listFiles()?.forEach { it.deleteRecursively() }
    }

    fun nombreDe(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) {
                        val n = c.getString(i)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        } catch (e: Exception) {
            // se usa el respaldo
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    fun tamanoDe(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (e: Exception) {
            // desconocido
        }
        return -1L
    }

    fun copiar(ctx: Context, uri: Uri, maxBytes: Long): Resultado {
        val nombre = nombreDe(ctx, uri)
        val seguro = nombre.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destino = File(carpetaAbiertos(ctx), "${System.currentTimeMillis()}_$seguro")
        val entrada = ctx.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
        var total = 0L
        try {
            entrada.use { i ->
                FileOutputStream(destino).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n.toLong()
                        if (total > maxBytes) {
                            throw IOException("El archivo supera el límite de ${Formato.bytes(maxBytes)} (ajustable en Ajustes)")
                        }
                        o.write(buf, 0, n)
                    }
                }
            }
        } catch (t: Throwable) {
            destino.delete()
            throw t
        }
        return Resultado(destino, nombre)
    }

    fun cabecera(archivo: File, n: Int): ByteArray {
        FileInputStream(archivo).use { i ->
            return Flujos.leerHasta(i, n)
        }
    }
}
