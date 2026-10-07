package com.carpe.gestorarchivos.data

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.zip.ZipFile

/** Lectores de solo lectura para DOCX y XLSX (zip + XML), sin librerías externas. */
object LectorOffice {

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun estiloBase(oscuro: Boolean): String {
        val fondo = if (oscuro) "#12121F" else "#FFFFFF"
        val texto = if (oscuro) "#F0EEF8" else "#1A1A2E"
        val borde = if (oscuro) "#3A3A55" else "#CFCFE0"
        val cab = if (oscuro) "#24243A" else "#EEEEF6"
        val enlace = if (oscuro) "#9C93FF" else "#4A3FCC"
        return "<meta name='viewport' content='width=device-width, initial-scale=1'><style>" +
            "body{margin:0;padding:12px;background:$fondo;color:$texto;font-family:sans-serif;font-size:15px;line-height:1.45;word-wrap:break-word}" +
            "p{margin:0 0 .6em 0}h1,h2,h3,h4,h5,h6{margin:.8em 0 .4em 0;line-height:1.25}" +
            "a{color:$enlace}img{max-width:100%;height:auto}" +
            "table{border-collapse:collapse;margin:.6em 0}td,th{border:1px solid $borde;padding:4px 8px;vertical-align:top}" +
            "th{background:$cab;font-weight:600;color:$texto}" +
            "th.n{text-align:right;color:#8A8AAA;font-weight:normal;position:sticky;left:0}" +
            ".nota{margin-top:12px;font-size:12px;color:#8A8AAA}" +
            "</style>"
    }

    fun paginaMensaje(mensaje: String, oscuro: Boolean): String =
        "<html><head><meta charset='utf-8'>" + estiloBase(oscuro) + "</head><body><p>" + esc(mensaje) + "</p></body></html>"

    private class Relacion(val destino: String, val externa: Boolean)

    private fun leerRelaciones(zip: ZipFile, ruta: String): Map<String, Relacion> {
        val e = zip.getEntry(ruta) ?: return emptyMap()
        val mapa = HashMap<String, Relacion>()
        val p = Xml.newPullParser()
        zip.getInputStream(e).use { inp ->
            p.setInput(inp, "UTF-8")
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id")
                    val destino = p.getAttributeValue(null, "Target")
                    if (id != null && destino != null) {
                        mapa[id] = Relacion(destino, "External".equals(p.getAttributeValue(null, "TargetMode"), true))
                    }
                }
                ev = p.next()
            }
        }
        return mapa
    }

    private fun resolver(base: String, destino: String): String {
        if (destino.startsWith("/")) return destino.trimStart('/')
        val partes = ArrayList<String>(base.trimEnd('/').split('/').filter { it.isNotEmpty() })
        for (seg in destino.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (partes.isNotEmpty()) partes.removeAt(partes.size - 1)
                else -> partes.add(seg)
            }
        }
        return partes.joinToString("/")
    }

    private fun saltar(p: XmlPullParser) {
        var d = 1
        while (d > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> d++
                XmlPullParser.END_TAG -> d--
                XmlPullParser.END_DOCUMENT -> return
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------ DOCX

    private class Parrafo {
        val contenido = StringBuilder()
        var nivelTitulo = 0
        var lista = false
        var nivelLista = 0
        var alineacion: String? = null
    }

    private fun nivelDeEstilo(id: String?): Int {
        val s = (id ?: return 0).lowercase()
        val digito = s.lastOrNull { it.isDigit() }?.digitToInt()
        return when {
            s.startsWith("subt") -> 2
            s.startsWith("heading") || s.startsWith("ttulo") || s.startsWith("title") ->
                if (digito != null && digito in 1..6) digito else 1
            else -> 0
        }
    }

    private fun colorValido(c: String?, oscuro: Boolean): String? {
        if (c == null || c == "auto" || !Regex("[0-9A-Fa-f]{6}").matches(c)) return null
        val r = c.substring(0, 2).toInt(16)
        val g = c.substring(2, 4).toInt(16)
        val b = c.substring(4, 6).toInt(16)
        if (oscuro && maxOf(r, g, b) < 0x50) return null
        if (!oscuro && minOf(r, g, b) > 0xE0) return null
        return c
    }

    private fun activo(p: XmlPullParser): Boolean {
        val v = p.getAttributeValue(null, "w:val") ?: return true
        return !(v == "0" || v.equals("false", true) || v == "none")
    }

    private fun imagenHtml(zip: ZipFile, rels: Map<String, Relacion>, id: String?, anchoPx: Int): String {
        if (id == null) return ""
        val rel = rels[id] ?: return ""
        if (rel.externa) return ""
        val ruta = resolver("word", rel.destino)
        val e = zip.getEntry(ruta) ?: return "[imagen]"
        if (e.size > 6_000_000) return "<i>[imagen demasiado grande]</i>"
        val ext = ruta.substringAfterLast('.').lowercase()
        val mime = when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> return "<i>[imagen ${esc(ext.uppercase())}]</i>"
        }
        val datos = zip.getInputStream(e).use { it.readBytes() }
        val b64 = Base64.encodeToString(datos, Base64.NO_WRAP)
        val ancho = if (anchoPx > 0) "width:${minOf(anchoPx, 2000)}px;" else ""
        return "<img style='max-width:100%;height:auto;$ancho' src='data:$mime;base64,$b64'>"
    }

    private fun cerrarParrafo(p: Parrafo): String {
        val c = p.contenido.toString()
        val estilo = StringBuilder()
        when (p.alineacion) {
            "center" -> estilo.append("text-align:center;")
            "right", "end" -> estilo.append("text-align:right;")
            "both" -> estilo.append("text-align:justify;")
        }
        if (p.lista) estilo.append("margin-left:${1.5 * (p.nivelLista + 1)}em;")
        val cuerpo = if (c.isEmpty()) "&nbsp;" else c
        return if (p.nivelTitulo in 1..6) {
            "<h${p.nivelTitulo} style='$estilo'>$cuerpo</h${p.nivelTitulo}>"
        } else {
            "<p style='$estilo'>${if (p.lista) "• " else ""}$cuerpo</p>"
        }
    }

    fun docxAHtml(archivo: File, oscuro: Boolean): String {
        return ZipFile(archivo).use { zip ->
            val entrada = zip.getEntry("word/document.xml")
                ?: throw IllegalArgumentException("No es un DOCX válido")
            val rels = leerRelaciones(zip, "word/_rels/document.xml.rels")
            val html = StringBuilder()
            html.append("<html><head><meta charset='utf-8'>").append(estiloBase(oscuro)).append("</head><body>")

            val parser = Xml.newPullParser()
            val pila = ArrayList<Parrafo>()
            val posTd = ArrayList<Int>()
            val enlaces = ArrayList<Boolean>()
            var enPPr = false
            var enRPr = false
            var negrita = false
            var cursiva = false
            var subrayado = false
            var tachado = false
            var color: String? = null
            var tam = 0
            var vertAlign = ""
            var anchoImagen = 0

            zip.getInputStream(entrada).use { input ->
                parser.setInput(input, "UTF-8")
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val actual = pila.lastOrNull()
                        when (parser.name) {
                            "mc:Fallback" -> saltar(parser)
                            "w:p" -> pila.add(Parrafo())
                            "w:pPr" -> enPPr = true
                            "w:pStyle" -> if (enPPr && actual != null) actual.nivelTitulo = nivelDeEstilo(parser.getAttributeValue(null, "w:val"))
                            "w:numPr" -> if (enPPr && actual != null) actual.lista = true
                            "w:ilvl" -> if (enPPr && actual != null) actual.nivelLista = parser.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 0
                            "w:jc" -> if (enPPr && actual != null) actual.alineacion = parser.getAttributeValue(null, "w:val")
                            "w:r" -> {
                                negrita = false; cursiva = false; subrayado = false; tachado = false
                                color = null; tam = 0; vertAlign = ""
                            }
                            "w:rPr" -> if (!enPPr) enRPr = true
                            "w:b" -> if (enRPr) negrita = activo(parser)
                            "w:i" -> if (enRPr) cursiva = activo(parser)
                            "w:u" -> if (enRPr) subrayado = activo(parser)
                            "w:strike" -> if (enRPr) tachado = activo(parser)
                            "w:color" -> if (enRPr) color = colorValido(parser.getAttributeValue(null, "w:val"), oscuro)
                            "w:sz" -> if (enRPr) tam = parser.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 0
                            "w:vertAlign" -> if (enRPr) vertAlign = parser.getAttributeValue(null, "w:val") ?: ""
                            "w:t" -> {
                                val texto = parser.nextText()
                                if (actual != null && texto.isNotEmpty()) {
                                    val abre = StringBuilder()
                                    val cierra = StringBuilder()
                                    if (negrita) { abre.append("<b>"); cierra.insert(0, "</b>") }
                                    if (cursiva) { abre.append("<i>"); cierra.insert(0, "</i>") }
                                    if (subrayado) { abre.append("<u>"); cierra.insert(0, "</u>") }
                                    if (tachado) { abre.append("<s>"); cierra.insert(0, "</s>") }
                                    if (vertAlign == "superscript") { abre.append("<sup>"); cierra.insert(0, "</sup>") }
                                    if (vertAlign == "subscript") { abre.append("<sub>"); cierra.insert(0, "</sub>") }
                                    val css = StringBuilder()
                                    if (color != null) css.append("color:#$color;")
                                    if (tam > 0) css.append("font-size:${tam / 2.0}pt;")
                                    if (css.isNotEmpty()) { abre.append("<span style='$css'>"); cierra.insert(0, "</span>") }
                                    actual.contenido.append(abre).append(esc(texto)).append(cierra)
                                }
                            }
                            "w:tab" -> if (!enPPr && actual != null) actual.contenido.append("&emsp;")
                            "w:br", "w:cr" -> if (actual != null) {
                                if (parser.getAttributeValue(null, "w:type") == "page") actual.contenido.append("<hr>")
                                else actual.contenido.append("<br>")
                            }
                            "w:tbl" -> html.append("<table>")
                            "w:tr" -> html.append("<tr>")
                            "w:tc" -> { posTd.add(html.length); html.append("<td>") }
                            "w:gridSpan" -> if (posTd.isNotEmpty()) {
                                val n = parser.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 1
                                val i = posTd[posTd.size - 1]
                                if (n > 1 && html.startsWith("<td>", i)) html.replace(i, i + 4, "<td colspan='$n'>")
                            }
                            "w:hyperlink" -> {
                                val id = parser.getAttributeValue(null, "r:id")
                                val rel = if (id != null) rels[id] else null
                                val destino = rel?.destino
                                val valido = rel != null && rel.externa && destino != null &&
                                    (destino.startsWith("http://") || destino.startsWith("https://") || destino.startsWith("mailto:"))
                                if (valido && actual != null) actual.contenido.append("<a href=\"${esc(destino!!)}\">")
                                enlaces.add(valido && actual != null)
                            }
                            "w:drawing", "w:pict" -> anchoImagen = 0
                            "wp:extent" -> anchoImagen = ((parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: 0L) / 9525L).toInt()
                            "a:blip" -> if (actual != null) {
                                actual.contenido.append(imagenHtml(zip, rels, parser.getAttributeValue(null, "r:embed"), anchoImagen))
                            }
                            "v:imagedata" -> if (actual != null) {
                                actual.contenido.append(imagenHtml(zip, rels, parser.getAttributeValue(null, "r:id"), 0))
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (parser.name) {
                            "w:pPr" -> enPPr = false
                            "w:rPr" -> enRPr = false
                            "w:p" -> if (pila.isNotEmpty()) {
                                val p = pila.removeAt(pila.size - 1)
                                val bloque = cerrarParrafo(p)
                                if (pila.isEmpty()) html.append(bloque)
                                else pila[pila.size - 1].contenido.append("<div>").append(bloque).append("</div>")
                            }
                            "w:hyperlink" -> if (enlaces.isNotEmpty()) {
                                val abierto = enlaces.removeAt(enlaces.size - 1)
                                if (abierto) pila.lastOrNull()?.contenido?.append("</a>")
                            }
                            "w:tbl" -> html.append("</table>")
                            "w:tr" -> html.append("</tr>")
                            "w:tc" -> {
                                html.append("</td>")
                                if (posTd.isNotEmpty()) posTd.removeAt(posTd.size - 1)
                            }
                        }
                    }
                    ev = parser.next()
                }
            }
            html.append("</body></html>")
            html.toString()
        }
    }

    // ------------------------------------------------------------------ XLSX

    class LibroXlsx private constructor(private val archivo: File) {

        val nombresHojas = ArrayList<String>()
        private val rutasHojas = ArrayList<String>()
        private val textos = ArrayList<String>()
        private val idsFormato = ArrayList<Int>()
        private val codigosFormato = HashMap<Int, String>()

        companion object {
            private const val MAX_FILAS = 5000
            private const val MAX_COLS = 200
            private const val MAX_CELDAS = 250_000
            private const val BASE_EXCEL_MS = -2209161600000L // 1899-12-30T00:00:00Z

            fun abrir(archivo: File): LibroXlsx {
                val libro = LibroXlsx(archivo)
                libro.cargar()
                return libro
            }

            private fun columnaDe(ref: String): Int {
                var n = 0
                for (c in ref) {
                    if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1)
                    else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1)
                    else break
                }
                return n - 1
            }

            private fun filaDe(ref: String): Int = ref.filter { it.isDigit() }.toIntOrNull() ?: 0

            private fun letrasColumna(indice: Int): String {
                var n = indice + 1
                val sb = StringBuilder()
                while (n > 0) {
                    val r = (n - 1) % 26
                    sb.insert(0, ('A' + r))
                    n = (n - 1) / 26
                }
                return sb.toString()
            }
        }

        private fun cargar() {
            ZipFile(archivo).use { zip ->
                val wb = zip.getEntry("xl/workbook.xml") ?: throw IllegalArgumentException("No es un XLSX válido")
                val rels = leerRelaciones(zip, "xl/_rels/workbook.xml.rels")
                val p = Xml.newPullParser()
                zip.getInputStream(wb).use { inp ->
                    p.setInput(inp, "UTF-8")
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                            val nombre = p.getAttributeValue(null, "name") ?: "Hoja"
                            val rid = p.getAttributeValue(null, "r:id")
                            val destino = if (rid != null) rels[rid]?.destino else null
                            if (destino != null) {
                                nombresHojas.add(nombre)
                                rutasHojas.add(resolver("xl", destino))
                            }
                        }
                        ev = p.next()
                    }
                }
                if (nombresHojas.isEmpty()) throw IllegalArgumentException("El libro no tiene hojas")

                // Cadenas compartidas
                zip.getEntry("xl/sharedStrings.xml")?.let { e ->
                    val q = Xml.newPullParser()
                    zip.getInputStream(e).use { inp ->
                        q.setInput(inp, "UTF-8")
                        var actual = StringBuilder()
                        var enPh = false
                        var ev = q.eventType
                        while (ev != XmlPullParser.END_DOCUMENT) {
                            if (ev == XmlPullParser.START_TAG) {
                                when (q.name) {
                                    "si" -> actual = StringBuilder()
                                    "rPh" -> enPh = true
                                    "t" -> { val t = q.nextText(); if (!enPh) actual.append(t) }
                                }
                            } else if (ev == XmlPullParser.END_TAG) {
                                when (q.name) {
                                    "rPh" -> enPh = false
                                    "si" -> textos.add(actual.toString())
                                }
                            }
                            ev = q.next()
                        }
                    }
                }

                // Estilos (para detectar fechas y formatos numéricos)
                zip.getEntry("xl/styles.xml")?.let { e ->
                    val q = Xml.newPullParser()
                    zip.getInputStream(e).use { inp ->
                        q.setInput(inp, "UTF-8")
                        var enXfs = false
                        var ev = q.eventType
                        while (ev != XmlPullParser.END_DOCUMENT) {
                            if (ev == XmlPullParser.START_TAG) {
                                when (q.name) {
                                    "numFmt" -> {
                                        val id = q.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                                        val codigo = q.getAttributeValue(null, "formatCode")
                                        if (id != null && codigo != null) codigosFormato[id] = codigo
                                    }
                                    "cellXfs" -> enXfs = true
                                    "xf" -> if (enXfs) idsFormato.add(q.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0)
                                }
                            } else if (ev == XmlPullParser.END_TAG && q.name == "cellXfs") {
                                enXfs = false
                            }
                            ev = q.next()
                        }
                    }
                }
            }
        }

        private fun codigoEsFecha(codigo: String): Boolean {
            val limpio = codigo
                .replace(Regex("\"[^\"]*\""), "")
                .replace(Regex("\\[[^\\]]*\\]"), "")
                .replace(Regex("\\\\."), "")
            return limpio.any { it.lowercaseChar() in "ymdhs" }
        }

        private fun esFecha(id: Int, codigo: String?): Boolean =
            id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58 ||
                (codigo != null && id >= 164 && codigoEsFecha(codigo))

        private fun fechaExcel(d: Double): String {
            val ms = Math.round(d * 86400000.0) + BASE_EXCEL_MS
            val entero = d.toLong()
            val frac = d - entero
            val patron = when {
                frac < 1e-9 -> "dd/MM/yyyy"
                entero == 0L -> "HH:mm:ss"
                else -> "dd/MM/yyyy HH:mm"
            }
            val f = SimpleDateFormat(patron, Locale.getDefault())
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date(ms))
        }

        private fun decimalesDe(codigo: String): Int? {
            val seccion = codigo.substringBefore(';')
                .replace(Regex("\"[^\"]*\""), "")
                .replace(Regex("\\[[^\\]]*\\]"), "")
            if (!seccion.any { it == '0' || it == '#' || it == '?' }) return null
            val punto = seccion.indexOf('.')
            if (punto < 0) return 0
            var n = 0
            for (i in punto + 1 until seccion.length) {
                if (seccion[i] == '0' || seccion[i] == '#' || seccion[i] == '?') n++ else break
            }
            return n
        }

        private fun general(d: Double): String {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
            return BigDecimal(d).round(MathContext(12)).stripTrailingZeros().toPlainString()
        }

        private fun numero(valor: String, estilo: Int): String {
            val d = valor.toDoubleOrNull() ?: return valor
            val id = idsFormato.getOrNull(estilo) ?: 0
            val codigo = codigosFormato[id]
            if (esFecha(id, codigo)) return fechaExcel(d)
            val porcentaje = id == 9 || id == 10 || codigo?.contains('%') == true
            val miles = id == 3 || id == 4 || codigo?.contains(',') == true
            val dec: Int? = when (id) {
                0 -> null
                1, 3, 9 -> 0
                2, 4, 10 -> 2
                else -> if (codigo != null) decimalesDe(codigo) else null
            }
            if (dec == null) return general(d)
            val v = if (porcentaje) d * 100 else d
            val patron = (if (miles) "#,##0" else "0") + (if (dec > 0) "." + "0".repeat(dec) else "")
            return DecimalFormat(patron, DecimalFormatSymbols.getInstance()).format(v) + (if (porcentaje) "%" else "")
        }

        private fun textoCelda(tipo: String?, valor: String?, estilo: Int): String {
            if (valor == null) return ""
            return when (tipo) {
                "s" -> valor.toIntOrNull()?.let { textos.getOrNull(it) } ?: ""
                "b" -> if (valor == "1") "VERDADERO" else "FALSO"
                "str", "inlineStr", "e", "d" -> valor
                else -> numero(valor, estilo)
            }
        }

        fun hojaAHtml(indice: Int, oscuro: Boolean): String {
            return ZipFile(archivo).use { zip ->
                val e = zip.getEntry(rutasHojas[indice]) ?: return paginaMensaje("No se encontró la hoja.", oscuro)
                val filas = TreeMap<Int, TreeMap<Int, String>>()
                val fusiones = ArrayList<IntArray>()
                var maxCol = 0
                var truncado = false
                val p = Xml.newPullParser()
                zip.getInputStream(e).use { inp ->
                    p.setInput(inp, "UTF-8")
                    var fila = 0
                    var colSig = 0
                    var ref: String? = null
                    var tipo: String? = null
                    var estilo = 0
                    var valor: String? = null
                    var ev = p.eventType
                    loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            when (p.name) {
                                "row" -> {
                                    fila = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (fila + 1)
                                    colSig = 0
                                    if (filas.size >= MAX_FILAS && !filas.containsKey(fila)) {
                                        truncado = true
                                        break@loop
                                    }
                                }
                                "c" -> {
                                    ref = p.getAttributeValue(null, "r")
                                    tipo = p.getAttributeValue(null, "t")
                                    estilo = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                                    valor = null
                                }
                                "v" -> valor = p.nextText()
                                "t" -> valor = (valor ?: "") + p.nextText()
                                "mergeCell" -> {
                                    val partes = (p.getAttributeValue(null, "ref") ?: "").split(':')
                                    if (partes.size == 2) {
                                        fusiones.add(intArrayOf(filaDe(partes[0]), columnaDe(partes[0]), filaDe(partes[1]), columnaDe(partes[1])))
                                    }
                                }
                            }
                        } else if (ev == XmlPullParser.END_TAG && p.name == "c") {
                            val r = ref
                            val col = if (r != null) columnaDe(r) else colSig
                            colSig = col + 1
                            val texto = textoCelda(tipo, valor, estilo)
                            if (texto.isNotEmpty() && col in 0 until MAX_COLS) {
                                filas.getOrPut(fila) { TreeMap() }[col] = texto
                                if (col + 1 > maxCol) maxCol = col + 1
                            }
                        }
                        ev = p.next()
                    }
                }

                if (filas.isEmpty()) return paginaMensaje("Esta hoja está vacía.", oscuro)

                // Celdas combinadas
                val spans = HashMap<Long, IntArray>()
                val cubiertas = HashSet<Long>()
                for (m in fusiones) {
                    val (r1, c1, r2, c2) = m.toList()
                    if (c1 < 0 || c2 < c1 || r2 < r1) continue
                    spans[r1.toLong() * 10000 + c1] = intArrayOf(r2 - r1 + 1, c2 - c1 + 1)
                    for (r in r1..r2) for (c in c1..c2) {
                        if (r != r1 || c != c1) cubiertas.add(r.toLong() * 10000 + c)
                    }
                }

                val maxFilasRender = minOf(filas.size, maxOf(1, MAX_CELDAS / maxOf(1, maxCol)))
                if (maxFilasRender < filas.size) truncado = true

                val html = StringBuilder()
                html.append("<html><head><meta charset='utf-8'>").append(estiloBase(oscuro)).append("</head><body>")
                html.append("<table><tr><th></th>")
                for (c in 0 until maxCol) html.append("<th>").append(letrasColumna(c)).append("</th>")
                html.append("</tr>")
                var contadas = 0
                for ((r, celdas) in filas) {
                    if (contadas++ >= maxFilasRender) break
                    html.append("<tr><th class='n'>").append(r).append("</th>")
                    for (c in 0 until maxCol) {
                        val clave = r.toLong() * 10000 + c
                        if (cubiertas.contains(clave)) continue
                        val s = spans[clave]
                        html.append("<td")
                        if (s != null) {
                            if (s[0] > 1) html.append(" rowspan='").append(s[0]).append("'")
                            if (s[1] > 1) html.append(" colspan='").append(s[1]).append("'")
                        }
                        html.append(">").append(esc(celdas[c] ?: "")).append("</td>")
                    }
                    html.append("</tr>")
                }
                html.append("</table>")
                if (truncado) html.append("<div class='nota'>Se muestran solo las primeras filas de la hoja (límite de vista previa).</div>")
                html.append("</body></html>")
                html.toString()
            }
        }
    }
}
