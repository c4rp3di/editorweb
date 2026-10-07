package com.carpe.gestorarchivos.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object GestorArchivos {

    class TextoLeido(val texto: String, val charset: Charset, val bom: Boolean, val crlf: Boolean) {
        val etiqueta: String
            get() = charset.displayName() + (if (bom) " (BOM)" else "") + " · " + (if (crlf) "CRLF" else "LF")
    }

    fun listar(carpeta: File, mostrarOcultos: Boolean = true): List<ArchivoItem> {
        val hijos = carpeta.listFiles() ?: return emptyList()
        return hijos
            .filter { mostrarOcultos || !it.name.startsWith(".") }
            .map { ArchivoItem.desde(it) }
    }

    fun crearCarpeta(padre: File, nombre: String): Boolean {
        if (nombre.isBlank() || nombre.contains('/')) return false
        return try { File(padre, nombre).let { !it.exists() && it.mkdirs() } } catch (e: Exception) { false }
    }

    fun crearArchivo(padre: File, nombre: String): Boolean {
        if (nombre.isBlank() || nombre.contains('/')) return false
        return try { File(padre, nombre).let { !it.exists() && it.createNewFile() } } catch (e: Exception) { false }
    }

    fun renombrar(archivo: File, nuevoNombre: String): Boolean {
        if (nuevoNombre.isBlank() || nuevoNombre.contains('/')) return false
        val destino = File(archivo.parentFile, nuevoNombre)
        if (destino.exists()) return false
        return try { archivo.renameTo(destino) } catch (e: Exception) { false }
    }

    fun borrarDefinitivo(archivo: File): Boolean =
        try { if (archivo.isDirectory) archivo.deleteRecursively() else archivo.delete() } catch (e: Exception) { false }

    /** Devuelve un destino que no exista: «nombre (1).ext», «nombre (2).ext»… */
    fun nombreLibre(padre: File, nombre: String): File {
        var f = File(padre, nombre)
        if (!f.exists()) return f
        val tienePunto = nombre.lastIndexOf('.') > 0
        val base = if (tienePunto) nombre.substringBeforeLast('.') else nombre
        val ext = if (tienePunto) "." + nombre.substringAfterLast('.') else ""
        var n = 1
        while (f.exists()) {
            f = File(padre, "$base ($n)$ext")
            n++
        }
        return f
    }

    fun esMismoODescendiente(destino: File, origen: File): Boolean {
        val d = destino.canonicalPath
        val o = origen.canonicalPath
        return d == o || d.startsWith(o + File.separator)
    }

    fun mover(origen: File, destinoPadre: File): Boolean {
        try {
            if (origen.parentFile?.canonicalPath == destinoPadre.canonicalPath) return true
            if (origen.isDirectory && esMismoODescendiente(destinoPadre, origen)) return false
            return moverComo(origen, nombreLibre(destinoPadre, origen.name))
        } catch (e: Exception) {
            return false
        }
    }

    fun moverComo(origen: File, destino: File): Boolean {
        if (origen.renameTo(destino)) return true
        return if (copiarRecursivo(origen, destino)) {
            borrarDefinitivo(origen)
        } else {
            destino.deleteRecursively()
            false
        }
    }

    fun copiar(origen: File, destinoPadre: File): Boolean {
        try {
            if (origen.isDirectory && esMismoODescendiente(destinoPadre, origen)) return false
            val destino = nombreLibre(destinoPadre, origen.name)
            val ok = copiarRecursivo(origen, destino)
            if (!ok) destino.deleteRecursively()
            return ok
        } catch (e: Exception) {
            return false
        }
    }

    private fun copiarRecursivo(origen: File, destino: File): Boolean {
        return try {
            if (origen.isDirectory) {
                if (!destino.exists() && !destino.mkdirs()) return false
                var ok = true
                for (hijo in origen.listFiles() ?: emptyArray()) {
                    ok = copiarRecursivo(hijo, File(destino, hijo.name)) && ok
                }
                ok
            } else {
                origen.inputStream().use { entrada ->
                    destino.outputStream().use { salida -> entrada.copyTo(salida) }
                }
                destino.setLastModified(origen.lastModified())
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun tamanoTotal(f: File): Long {
        if (!f.isDirectory) return f.length()
        var total = 0L
        for (h in f.listFiles() ?: emptyArray()) total += tamanoTotal(h)
        return total
    }

    /** (bytes totales, nº de archivos) de una carpeta. */
    fun resumenCarpeta(f: File): Pair<Long, Int> {
        if (!f.isDirectory) return Pair(f.length(), 1)
        var bytes = 0L
        var n = 0
        for (h in f.listFiles() ?: emptyArray()) {
            val (b, c) = resumenCarpeta(h)
            bytes += b
            n += c
        }
        return Pair(bytes, n)
    }

    // ---------- Texto ----------

    fun leerTextoDetectado(archivo: File): TextoLeido? {
        return try {
            val bytes = archivo.readBytes()
            var offset = 0
            var charset: Charset = Charsets.UTF_8
            var bom = false
            var utf16 = false
            when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> {
                    offset = 3; bom = true
                }
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                    charset = Charsets.UTF_16LE; offset = 2; bom = true; utf16 = true
                }
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                    charset = Charsets.UTF_16BE; offset = 2; bom = true; utf16 = true
                }
            }
            if (!utf16) {
                val limite = minOf(bytes.size, 8000)
                for (i in 0 until limite) if (bytes[i].toInt() == 0) return null // binario
            }
            var texto: String
            if (!bom || !utf16) {
                if (!utf16) {
                    texto = try {
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                            .toString()
                    } catch (e: CharacterCodingException) {
                        charset = Charset.forName("windows-1252")
                        String(bytes, 0, bytes.size, charset)
                    }
                } else {
                    texto = String(bytes, offset, bytes.size - offset, charset)
                }
            } else {
                texto = String(bytes, offset, bytes.size - offset, charset)
            }
            val crlf = texto.contains("\r\n")
            if (crlf) texto = texto.replace("\r\n", "\n")
            TextoLeido(texto, charset, bom, crlf)
        } catch (e: Exception) {
            null
        }
    }

    fun escribirTexto(archivo: File, contenido: String, ref: TextoLeido? = null): Boolean {
        return try {
            val charset = ref?.charset ?: Charsets.UTF_8
            val texto = if (ref?.crlf == true) contenido.replace("\n", "\r\n") else contenido
            val cuerpo = texto.toByteArray(charset)
            val prefijo: ByteArray = if (ref?.bom == true) {
                when (charset) {
                    Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                    Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                    Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                    else -> ByteArray(0)
                }
            } else ByteArray(0)
            val padre = archivo.parentFile
            val tmp = if (padre != null) File(padre, ".${archivo.name}.tmp") else null
            var hecho = false
            if (tmp != null) {
                try {
                    tmp.outputStream().use { it.write(prefijo); it.write(cuerpo) }
                    hecho = tmp.renameTo(archivo)
                } catch (e: Exception) {
                    hecho = false
                }
                if (!hecho) tmp.delete()
            }
            if (!hecho) {
                archivo.outputStream().use { it.write(prefijo); it.write(cuerpo) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Intents ----------

    fun uriDe(context: Context, archivo: File) =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", archivo)

    /** Muestra siempre el selector de apps del sistema («Abrir con…»), aunque haya una app predeterminada. */
    fun abrirConOtraApp(context: Context, archivo: File): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriDe(context, archivo), ArchivoItem.adivinarMime(archivo.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Abrir con"))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Abre un HTML directamente en el navegador del dispositivo. Devuelve false si no pudo. */
    fun abrirEnNavegador(context: Context, archivo: File): Boolean {
        return try {
            val pm = context.packageManager
            val sondeo = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val predeterminado = pm.resolveActivity(sondeo, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            val paquete = if (predeterminado != null && predeterminado != "android") predeterminado
            else pm.queryIntentActivities(sondeo, 0).firstOrNull()?.activityInfo?.packageName
            if (paquete == null) return false
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriDe(context, archivo), "text/html")
                setPackage(paquete)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun compartir(context: Context, archivos: List<File>): Boolean {
        val soloArchivos = archivos.filter { it.isFile }
        if (soloArchivos.isEmpty()) return false
        return try {
            val uris = ArrayList(soloArchivos.map { uriDe(context, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = ArchivoItem.adivinarMime(soloArchivos[0].name)
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Compartir con"))
            true
        } catch (e: Exception) {
            false
        }
    }
}
