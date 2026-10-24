package com.docreader.editor.data.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TreeMap
import javax.inject.Inject

class SheetModel(
    val name: String,
    val path: String,
    val xml: String,
    val orig: Map<String, String>,
    val rows: Int,
    val cols: Int
) {
    /** Celdas modificadas: referencia ("B2") -> texto nuevo. */
    val edits = mutableMapOf<String, String>()
    fun get(ref: String) = edits[ref] ?: orig[ref] ?: ""
}

class XlsxModel(val bytes: ByteArray, val sheets: List<SheetModel>) {
    fun isDirty() = sheets.any { it.edits.isNotEmpty() }
}

object XlsxUtil {
    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { n--; sb.append('A' + n % 26); n /= 26 }
        return sb.reverse().toString()
    }

    fun colIndex(ref: String): Int {
        var c = 0
        for (ch in ref) { if (ch.isLetter()) c = c * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return c - 1
    }

    fun rowNum(ref: String): Int = ref.dropWhile { it.isLetter() }.toIntOrNull() ?: 1
}

/**
 * XLSX editable celda a celda (texto, números y fórmulas "=..."). Solo se reescriben las filas/celdas
 * tocadas; estilos, anchos, gráficos, etc. se conservan. Al editar se elimina calcChain.xml
 * (Excel lo regenera) y se pide recálculo al abrir.
 */
class XlsxEngine @Inject constructor() : DocumentEngine {
    override val supportedExtensions = setOf("xlsx")
    override val supportsEditing = true
    override val requiresOcr = false

    private val cellRe = Regex("<c\\s([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val rowRe = Regex("<row\\s([^>]*?)(?:/>|>(.*?)</row>)", RegexOption.DOT_MATCHES_ALL)
    private val sheetDataRe = Regex("<sheetData(?:\\s[^>]*)?(?:/>|>(.*?)</sheetData>)", RegexOption.DOT_MATCHES_ALL)

    override suspend fun open(context: Context, uri: Uri, extension: String) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("No se pudo abrir el archivo")
        val files = OoxmlZip.read(bytes) {
            it == "xl/workbook.xml" || it == "xl/_rels/workbook.xml.rels" || it == "xl/sharedStrings.xml" ||
                (it.startsWith("xl/worksheets/") && it.endsWith(".xml"))
        }
        val shared = files["xl/sharedStrings.xml"]?.let { parseShared(it.toString(Charsets.UTF_8)) } ?: emptyList()
        val sheets = sheetList(files).mapNotNull { (name, path) ->
            val xml = files[path]?.toString(Charsets.UTF_8) ?: return@mapNotNull null
            parseSheet(name, path, xml, shared)
        }
        if (sheets.isEmpty()) throw java.io.IOException("XLSX no válido")
        val text = sheets.joinToString("\n") { s -> s.orig.values.filter { it.isNotBlank() }.joinToString(" ") }
        DocumentHandle(uri, extension, text, XlsxModel(bytes, sheets))
    }

    private fun sheetList(files: Map<String, ByteArray>): List<Pair<String, String>> {
        val wb = files["xl/workbook.xml"]?.toString(Charsets.UTF_8)
        val rels = files["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8)
        if (wb != null && rels != null) {
            val targets = Regex("<Relationship\\s[^>]*?/>").findAll(rels).associate {
                val id = OoxmlZip.attr(it.value, "Id") ?: ""
                val t = OoxmlZip.attr(it.value, "Target") ?: ""
                id to (if (t.startsWith("/")) t.removePrefix("/") else "xl/$t")
            }
            val list = Regex("<sheet\\s[^>]*?/>").findAll(wb).mapNotNull {
                val name = OoxmlZip.attr(it.value, "name")?.let(OoxmlZip::unescape) ?: return@mapNotNull null
                val rid = OoxmlZip.attr(it.value, "id") ?: return@mapNotNull null
                val path = targets[rid] ?: return@mapNotNull null
                name to path
            }.toList()
            if (list.isNotEmpty()) return list
        }
        return files.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted()
            .mapIndexed { i, p -> "Hoja${i + 1}" to p }
    }

    private fun parseShared(xml: String): List<String> =
        Regex("<si>(.*?)</si>|<si\\s[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
            val body = si.groupValues[1].ifEmpty { si.groupValues[2] }
            Regex("<t(?:\\s[^>]*)?>([^<]*)</t>").findAll(body).joinToString("") { OoxmlZip.unescape(it.groupValues[1]) }
        }.toList()

    private fun parseSheet(name: String, path: String, xml: String, shared: List<String>): SheetModel {
        val orig = mutableMapOf<String, String>()
        var maxRow = 0
        var maxCol = 0
        val body = sheetDataRe.find(xml)?.groupValues?.get(1).orEmpty()
        for (m in cellRe.findAll(body)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val ref = OoxmlZip.attr(attrs, "r") ?: continue
            val t = OoxmlZip.attr(attrs, "t")
            val v = Regex("<v>([^<]*)</v>").find(inner)?.groupValues?.get(1)?.let(OoxmlZip::unescape) ?: ""
            val f = Regex("<f(?:\\s[^>]*)?>([^<]*)</f>").find(inner)?.groupValues?.get(1)
            val text = when {
                f != null && f.isNotEmpty() -> "=" + OoxmlZip.unescape(f)
                t == "s" -> shared.getOrElse(v.trim().toIntOrNull() ?: -1) { "" }
                t == "inlineStr" -> Regex("<t(?:\\s[^>]*)?>([^<]*)</t>").findAll(inner)
                    .joinToString("") { OoxmlZip.unescape(it.groupValues[1]) }
                t == "b" -> if (v == "1") "TRUE" else "FALSE"
                else -> v
            }
            if (text.isNotEmpty()) orig[ref] = text
            maxRow = maxOf(maxRow, XlsxUtil.rowNum(ref))
            maxCol = maxOf(maxCol, XlsxUtil.colIndex(ref) + 1)
        }
        val rows = maxOf(maxRow + 10, 20).coerceAtMost(5000)
        val cols = maxOf(maxCol + 2, 8).coerceAtMost(100)
        return SheetModel(name, path, xml, orig, rows, cols)
    }

    override suspend fun extractText(context: Context, handle: DocumentHandle) = handle.text

    override suspend fun save(context: Context, handle: DocumentHandle, outputUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val m = handle.model as XlsxModel
                val replace = mutableMapOf<String, ByteArray>()
                val drop = mutableSetOf<String>()
                val edited = m.sheets.filter { it.edits.isNotEmpty() }
                for (s in edited) replace[s.path] = patchSheet(s).toByteArray(Charsets.UTF_8)
                if (edited.isNotEmpty()) {
                    drop.add("xl/calcChain.xml")
                    val extra = OoxmlZip.read(m.bytes) {
                        it == "[Content_Types].xml" || it == "xl/_rels/workbook.xml.rels" || it == "xl/workbook.xml"
                    }
                    extra["[Content_Types].xml"]?.toString(Charsets.UTF_8)?.let {
                        replace["[Content_Types].xml"] =
                            it.replace(Regex("<Override\\s[^>]*calcChain[^>]*/>"), "").toByteArray(Charsets.UTF_8)
                    }
                    extra["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8)?.let {
                        replace["xl/_rels/workbook.xml.rels"] =
                            it.replace(Regex("<Relationship\\s[^>]*calcChain[^>]*/>"), "").toByteArray(Charsets.UTF_8)
                    }
                    extra["xl/workbook.xml"]?.toString(Charsets.UTF_8)?.let { wb ->
                        val calc = Regex("<calcPr\\b([^>]*?)(/?)>").find(wb)
                        if (calc != null && !calc.groupValues[1].contains("fullCalcOnLoad")) {
                            val patched = wb.replaceRange(
                                calc.range, "<calcPr${calc.groupValues[1]} fullCalcOnLoad=\"1\"${calc.groupValues[2]}>"
                            )
                            replace["xl/workbook.xml"] = patched.toByteArray(Charsets.UTF_8)
                        }
                    }
                }
                val out = OoxmlZip.rewrite(m.bytes, replace, drop)
                context.contentResolver.openOutputStream(outputUri, "wt")?.use { it.write(out) } != null
            } catch (e: Exception) {
                false
            }
        }

    private class RowX(var attrs: String, val cells: TreeMap<Int, String> = TreeMap())

    private fun patchSheet(s: SheetModel): String {
        val sd = sheetDataRe.find(s.xml) ?: return s.xml
        val rows = TreeMap<Int, RowX>()
        for (r in rowRe.findAll(sd.groupValues[1])) {
            val attrs = r.groupValues[1]
            val num = OoxmlZip.attr(attrs, "r")?.toIntOrNull() ?: continue
            val row = RowX(attrs.replace(Regex("\\sspans=\"[^\"]*\""), ""))
            for (c in cellRe.findAll(r.groupValues[2])) {
                val ref = OoxmlZip.attr(c.groupValues[1], "r") ?: continue
                row.cells[XlsxUtil.colIndex(ref)] = c.value
            }
            rows[num] = row
        }
        for ((ref, value) in s.edits) {
            val rn = XlsxUtil.rowNum(ref)
            val ci = XlsxUtil.colIndex(ref)
            val row = rows.getOrPut(rn) { RowX("r=\"$rn\"") }
            val style = row.cells[ci]?.let { OoxmlZip.attr(it.substringBefore('>'), "s") }
            val raw = buildCell(ref, style, value)
            if (raw.isEmpty()) row.cells.remove(ci) else row.cells[ci] = raw
        }
        val sb = StringBuilder("<sheetData>")
        for ((_, row) in rows) {
            if (row.cells.isEmpty()) sb.append("<row ${row.attrs}/>")
            else sb.append("<row ${row.attrs}>").append(row.cells.values.joinToString("")).append("</row>")
        }
        sb.append("</sheetData>")
        return s.xml.replaceRange(sd.range, sb.toString())
    }

    private val numRe = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")

    private fun buildCell(ref: String, style: String?, v: String): String {
        val s = if (style != null) " s=\"$style\"" else ""
        return when {
            v.isEmpty() -> if (style != null) "<c r=\"$ref\"$s/>" else ""
            v.startsWith("=") && v.length > 1 ->
                "<c r=\"$ref\"$s><f>${OoxmlZip.escape(v.substring(1))}</f></c>"
            numRe.matches(v) && !(v.length > 1 && v.startsWith("0") && !v.startsWith("0.")) ->
                "<c r=\"$ref\"$s><v>$v</v></c>"
            else ->
                "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${OoxmlZip.escape(v)}</t></is></c>"
        }
    }
}
