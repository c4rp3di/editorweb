package com.carpe.panoptes.core.files

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Decodifica el AndroidManifest.xml binario (AXML) de un APK a XML legible.
 * Implementación propia, sin dependencias: los nombres de atributos salen de la tabla de
 * cadenas del propio fichero y los valores que son referencias a recursos se muestran como
 * "@0x7f...". No resuelve recursos (eso requeriría resources.arsc), pero es el manifest real.
 */
object AxmlDecoder {

    private const val MAX_MANIFEST = 16 * 1024 * 1024
    private const val NS_ANDROID = "http://schemas.android.com/apk/res/android"

    fun decodificarDesdeApk(apk: File): String? =
        ZipFile(apk).use { zip ->
            val e = zip.getEntry("AndroidManifest.xml") ?: return null
            if (e.size > MAX_MANIFEST) return null
            decodificar(zip.getInputStream(e).use { it.readBytes() })
        }

    fun decodificar(datos: ByteArray): String? {
        return try {
            val b = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN)
            if (datos.size < 8 || (b.getShort(0).toInt() and 0xFFFF) != 0x0003) return null
            var pos = (b.getShort(2).toInt() and 0xFFFF)
            val total = minOf(b.getInt(4), datos.size)
            var cadenas: List<String> = emptyList()
            var ids = IntArray(0)
            val prefijos = LinkedHashMap<String, String>() // uri -> prefijo
            val pendientes = ArrayList<Pair<String, String>>() // declaraciones aún sin escribir
            val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            var nivel = 0
            var abierto = false // hay un "<tag ..." sin cerrar con ">"

            fun cerrarAbierto() { if (abierto) { sb.append(">\n"); abierto = false } }
            fun s(i: Int) = if (i >= 0 && i < cadenas.size) cadenas[i] else ""

            while (pos + 8 <= total) {
                val tipo = b.getShort(pos).toInt() and 0xFFFF
                val tam = b.getInt(pos + 4)
                if (tam < 8 || pos + tam > datos.size) break
                when (tipo) {
                    0x0001 -> cadenas = leerPool(b, pos)
                    0x0180 -> {
                        val n = (tam - 8) / 4
                        ids = IntArray(n) { b.getInt(pos + 8 + it * 4) }
                    }
                    0x0100 -> {
                        val prefijo = s(b.getInt(pos + 16)); val uri = s(b.getInt(pos + 20))
                        prefijos[uri] = prefijo
                        pendientes.add(prefijo to uri)
                    }
                    0x0101 -> { }
                    0x0102 -> {
                        cerrarAbierto()
                        val ns = b.getInt(pos + 16); val nombre = b.getInt(pos + 20)
                        val attrStart = b.getShort(pos + 24).toInt() and 0xFFFF
                        val attrCount = b.getShort(pos + 28).toInt() and 0xFFFF
                        sb.append("  ".repeat(nivel)).append('<').append(s(nombre))
                        for ((p, u) in pendientes) {
                            sb.append(" xmlns:").append(p).append("=\"").append(esc(u)).append('"')
                        }
                        pendientes.clear()
                        val base = pos + 16 + attrStart
                        for (i in 0 until attrCount) {
                            val o = base + i * 20
                            if (o + 20 > datos.size) break
                            val ans = b.getInt(o); val an = b.getInt(o + 4); val raw = b.getInt(o + 8)
                            val dt = b.get(o + 15).toInt() and 0xFF; val dato = b.getInt(o + 16)
                            var nom = s(an)
                            if (nom.isEmpty()) nom = "attr_0x" + Integer.toHexString(ids.getOrElse(an) { 0 })
                            val pre = if (ans >= 0) prefijos[s(ans)] ?: if (s(ans) == NS_ANDROID) "android" else "" else ""
                            sb.append(' ')
                            if (pre.isNotEmpty()) sb.append(pre).append(':')
                            sb.append(nom).append("=\"").append(esc(valor(dt, dato, raw, ::s))).append('"')
                        }
                        abierto = true
                        nivel++
                    }
                    0x0103 -> {
                        nivel = maxOf(0, nivel - 1)
                        if (abierto) { sb.append("/>\n"); abierto = false }
                        else sb.append("  ".repeat(nivel)).append("</").append(s(b.getInt(pos + 20))).append(">\n")
                    }
                    0x0104 -> {
                        cerrarAbierto()
                        sb.append("  ".repeat(nivel)).append(esc(s(b.getInt(pos + 16)))).append('\n')
                    }
                }
                pos += tam
            }
            cerrarAbierto()
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun valor(tipo: Int, dato: Int, raw: Int, s: (Int) -> String): String = when (tipo) {
        0x03 -> if (raw >= 0) s(raw) else s(dato)
        0x10 -> dato.toString()
        0x11 -> "0x" + Integer.toHexString(dato)
        0x12 -> if (dato != 0) "true" else "false"
        0x01 -> "@0x" + Integer.toHexString(dato)
        0x02 -> "?0x" + Integer.toHexString(dato)
        0x04 -> java.lang.Float.intBitsToFloat(dato).toString()
        in 0x1c..0x1f -> "#" + String.format("%08x", dato)
        else -> if (raw >= 0) s(raw) else "0x" + Integer.toHexString(dato)
    }

    private fun esc(t: String): String = buildString {
        for (c in t) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); else -> append(c)
        }
    }

    private fun leerPool(b: ByteBuffer, ini: Int): List<String> {
        val n = b.getInt(ini + 8)
        val flags = b.getInt(ini + 16)
        val inicioCad = ini + b.getInt(ini + 20)
        val utf8 = (flags and 0x100) != 0
        val lista = ArrayList<String>(n)
        for (i in 0 until n) {
            var p = inicioCad + b.getInt(ini + 28 + i * 4)
            try {
                if (utf8) {
                    var l = b.get(p).toInt() and 0xFF; p++
                    if (l and 0x80 != 0) { l = ((l and 0x7F) shl 8) or (b.get(p).toInt() and 0xFF); p++ }
                    var bl = b.get(p).toInt() and 0xFF; p++
                    if (bl and 0x80 != 0) { bl = ((bl and 0x7F) shl 8) or (b.get(p).toInt() and 0xFF); p++ }
                    val arr = ByteArray(bl); for (k in 0 until bl) arr[k] = b.get(p + k)
                    lista.add(String(arr, Charsets.UTF_8))
                } else {
                    var l = b.getShort(p).toInt() and 0xFFFF; p += 2
                    if (l and 0x8000 != 0) { l = ((l and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF); p += 2 }
                    val sbd = StringBuilder(l)
                    for (k in 0 until l) sbd.append((b.getShort(p + k * 2).toInt() and 0xFFFF).toChar())
                    lista.add(sbd.toString())
                }
            } catch (e: Exception) { lista.add("") }
        }
        return lista
    }
}
