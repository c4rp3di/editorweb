package com.tunombre.gestorarchivos.data

import java.io.File

data class ArchivoItem(
    val archivo: File,
    val nombre: String,
    val esCarpeta: Boolean,
    val tamano: Long = 0,
    val mime: String? = null,
    val ultimaModificacion: Long = 0
) {
    val tamanoLegible: String
        get() = when {
            esCarpeta -> ""
            tamano < 1024 -> "$tamano B"
            tamano < 1024 * 1024 -> "%.1f KB".format(tamano / 1024.0)
            tamano < 1024L * 1024 * 1024 -> "%.1f MB".format(tamano / (1024.0 * 1024))
            else -> "%.2f GB".format(tamano / (1024.0 * 1024 * 1024))
        }

    val clave: String get() = archivo.absolutePath

    companion object {
        fun desde(file: File): ArchivoItem {
            val nombre = file.name
            val esDir = file.isDirectory
            val mime = if (esDir) null else adivinarMime(nombre)
            return ArchivoItem(
                archivo = file,
                nombre = nombre,
                esCarpeta = esDir,
                tamano = if (esDir) 0 else file.length(),
                mime = mime,
                ultimaModificacion = file.lastModified()
            )
        }

        private fun adivinarMime(nombre: String): String {
            val ext = nombre.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "txt", "md", "log", "ini", "conf", "properties" -> "text/plain"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "csv" -> "text/csv"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "kt", "java", "py", "sh", "yml", "yaml" -> "text/plain"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "m4a" -> "audio/mp4"
                "flac" -> "audio/flac"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "rar" -> "application/vnd.rar"
                "7z" -> "application/x-7z-compressed"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"
                "apk" -> "application/vnd.android.package-archive"
                else -> "*/*"
            }
        }
    }
}