package com.carpe.descompresor.apk

import com.carpe.descompresor.util.Flujos
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.CRC32

object Reempaquetador {
    private fun esFirma(n: String): Boolean {
        if (!n.startsWith("META-INF/")) return false
        val u = n.uppercase()
        return u == "META-INF/MANIFEST.MF" || u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC")
    }

    private fun crcDe(f: File): Long {
        val c = CRC32()
        FileInputStream(f).use { i ->
            val b = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(b)
                if (n < 0) break
                c.update(b, 0, n)
            }
        }
        return c.value
    }

    private fun escribirNuevo(out: ZipArchiveOutputStream, nombre: String, f: File, almacenado: Boolean) {
        val ne = ZipArchiveEntry(nombre)
        if (almacenado) {
            ne.method = ZipArchiveOutputStream.STORED
            ne.size = f.length()
            ne.crc = crcDe(f)
        } else {
            ne.method = ZipArchiveOutputStream.DEFLATED
        }
        out.putArchiveEntry(ne)
        FileInputStream(f).use { Flujos.copiar(it, out, 64 * 1024) }
        out.closeArchiveEntry()
    }

    /** Copia el ZIP original aplicando los cambios; descarta firmas antiguas y NO alinea ni firma. */
    fun reconstruir(origen: File, cambios: CambiosApk, destino: File) {
        if (destino.exists()) destino.delete()
        ZipFile(origen).use { zf ->
            ZipArchiveOutputStream(destino).use { out ->
                val vistos = HashSet<String>()
                val en = zf.entries
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val n = e.name
                    if (esFirma(n)) continue
                    if (n in cambios.eliminados) continue
                    vistos.add(n)
                    val rep = cambios.reemplazos[n]
                    if (rep != null) {
                        escribirNuevo(out, n, rep, e.method == ZipArchiveOutputStream.STORED)
                    } else {
                        val raw = zf.getRawInputStream(e) ?: throw IOException("No se pudo leer $n")
                        out.addRawArchiveEntry(e, raw)
                    }
                }
                for ((n, f) in cambios.reemplazos) {
                    if (n !in vistos) escribirNuevo(out, n, f, false)
                }
            }
        }
    }
}
