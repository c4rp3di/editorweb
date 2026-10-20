package com.carpe.gestorarchivos.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object Compresor {

    fun comprimir(origenes: List<File>, destinoZip: File, progreso: (String) -> Unit = {}): Boolean {
        return try {
            val excluir = destinoZip.canonicalPath
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destinoZip))).use { zos ->
                for (o in origenes) agregar(zos, o, o.name, excluir, progreso)
            }
            true
        } catch (e: Exception) {
            destinoZip.delete()
            false
        }
    }

    private fun agregar(zos: ZipOutputStream, f: File, ruta: String, excluir: String, progreso: (String) -> Unit) {
        if (f.canonicalPath == excluir || !f.canRead()) return
        if (f.isDirectory) {
            val hijos = f.listFiles() ?: emptyArray()
            if (hijos.isEmpty()) {
                zos.putNextEntry(ZipEntry("$ruta/"))
                zos.closeEntry()
            }
            for (h in hijos) agregar(zos, h, "$ruta/${h.name}", excluir, progreso)
        } else {
            progreso(f.name)
            val entrada = ZipEntry(ruta)
            entrada.time = f.lastModified()
            zos.putNextEntry(entrada)
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    /** Extrae el ZIP en [destino] (protegido contra rutas maliciosas «zip-slip»). */
    fun extraer(zip: File, destino: File, progreso: (String) -> Unit = {}): Boolean {
        destino.mkdirs()
        return try {
            extraerCon(zip, destino, Charsets.UTF_8, progreso)
        } catch (e: IllegalArgumentException) {
            // nombres con codificación antigua: reintento en ISO-8859-1
            destino.deleteRecursively()
            destino.mkdirs()
            try { extraerCon(zip, destino, Charsets.ISO_8859_1, progreso) } catch (e2: Exception) { false }
        } catch (e: Exception) {
            false
        }
    }

    private fun extraerCon(zip: File, destino: File, charset: Charset, progreso: (String) -> Unit): Boolean {
        val base = destino.canonicalPath
        ZipFile(zip, charset).use { zf ->
            val entradas = zf.entries()
            while (entradas.hasMoreElements()) {
                val e = entradas.nextElement()
                val salida = File(destino, e.name)
                val canon = salida.canonicalPath
                if (canon != base && !canon.startsWith(base + File.separator)) {
                    throw SecurityException("Ruta no permitida: ${e.name}")
                }
                if (e.isDirectory) {
                    salida.mkdirs()
                } else {
                    salida.parentFile?.mkdirs()
                    progreso(e.name.substringAfterLast('/'))
                    zf.getInputStream(e).use { inp ->
                        salida.outputStream().use { out -> inp.copyTo(out) }
                    }
                    if (e.time > 0) salida.setLastModified(e.time)
                }
            }
        }
        return true
    }
}
