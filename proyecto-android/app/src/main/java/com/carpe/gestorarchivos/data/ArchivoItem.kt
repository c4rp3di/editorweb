package com.carpe.gestorarchivos.data

import java.io.File

enum class Categoria {
    CARPETA, IMAGEN, VIDEO, AUDIO, TEXTO, PDF, DOCUMENTO, HOJA, PRESENTACION, COMPRIMIDO, APK, OTRO
}

enum class Filtro {
    TODOS, IMAGENES, VIDEOS, AUDIO, DOCUMENTOS, COMPRIMIDOS, OTROS;

    /** ¿Pertenece la categoría a este grupo de filtro? */
    fun incluye(c: Categoria): Boolean = when (this) {
        TODOS -> true
        IMAGENES -> c == Categoria.IMAGEN
        VIDEOS -> c == Categoria.VIDEO
        AUDIO -> c == Categoria.AUDIO
        DOCUMENTOS -> c == Categoria.TEXTO || c == Categoria.PDF || c == Categoria.DOCUMENTO ||
            c == Categoria.HOJA || c == Categoria.PRESENTACION
        COMPRIMIDOS -> c == Categoria.COMPRIMIDO || c == Categoria.APK
        OTROS -> c == Categoria.OTRO
    }

    /**
     * Con un filtro activo (distinto de TODOS) las carpetas se ocultan.
     * [extensiones] vacío = todas las extensiones del grupo; si no, solo las indicadas.
     */
    fun admite(item: ArchivoItem, extensiones: Set<String> = emptySet()): Boolean {
        if (this == TODOS) return true
        if (item.esCarpeta) return false
        if (!incluye(item.categoria)) return false
        return extensiones.isEmpty() || item.extension in extensiones
    }

    companion object {
        /** Texto para una extensión (la cadena vacía son archivos sin extensión). */
        fun etiquetaExtension(ext: String): String = if (ext.isEmpty()) "(sin extensión)" else ext
    }
}

data class ArchivoItem(
    val archivo: File,
    val nombre: String,
    val esCarpeta: Boolean,
    val tamano: Long = 0,
    val mime: String? = null,
    val ultimaModificacion: Long = 0
) {
    val extension: String get() = nombre.substringAfterLast('.', "").lowercase()
    val categoria: Categoria get() = if (esCarpeta) Categoria.CARPETA else categoriaDe(extension)
    val tamanoLegible: String get() = if (esCarpeta) "" else Formato.tamano(tamano)
    val clave: String get() = archivo.absolutePath

    companion object {
        private val IMAGENES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
        private val VIDEOS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
        private val AUDIOS = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")
        private val TEXTOS = setOf(
            "txt", "md", "log", "json", "xml", "csv", "html", "htm", "css", "js", "kt", "java", "py",
            "sh", "yml", "yaml", "ini", "conf", "properties", "ts", "tsx", "jsx", "c", "cpp", "h",
            "sql", "gradle", "kts", "bat", "tex", "srt", "toml", "svg", "env", "gitignore"
        )
        private val DOCUMENTOS = setOf("doc", "docx", "odt", "rtf")
        private val HOJAS = setOf("xls", "xlsx", "xlsm", "ods")
        private val PRESENTACIONES = setOf("ppt", "pptx", "odp")
        private val COMPRIMIDOS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "z", "cab")
        private val PAQUETES = setOf("apk", "apks", "xapk", "apkm", "aab")

        private val MIMES = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
            "svg" to "image/svg+xml",
            "mp4" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo",
            "mov" to "video/quicktime", "webm" to "video/webm", "3gp" to "video/3gpp", "m4v" to "video/mp4",
            "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "m4a" to "audio/mp4",
            "flac" to "audio/flac", "aac" to "audio/aac", "opus" to "audio/ogg",
            "json" to "application/json", "xml" to "application/xml", "csv" to "text/csv",
            "html" to "text/html", "htm" to "text/html", "css" to "text/css",
            "js" to "application/javascript",
            "pdf" to "application/pdf", "zip" to "application/zip", "rar" to "application/vnd.rar",
            "7z" to "application/x-7z-compressed", "tar" to "application/x-tar", "gz" to "application/gzip",
            "tgz" to "application/gzip", "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
            "apks" to "application/octet-stream", "xapk" to "application/octet-stream",
            "apkm" to "application/octet-stream", "aab" to "application/octet-stream",
            "apk" to "application/vnd.android.package-archive",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "odt" to "application/vnd.oasis.opendocument.text",
            "ods" to "application/vnd.oasis.opendocument.spreadsheet",
            "odp" to "application/vnd.oasis.opendocument.presentation",
            "rtf" to "application/rtf", "epub" to "application/epub+zip"
        )

        fun categoriaDe(ext: String): Categoria = when (ext) {
            in IMAGENES -> Categoria.IMAGEN
            in VIDEOS -> Categoria.VIDEO
            in AUDIOS -> Categoria.AUDIO
            in TEXTOS -> Categoria.TEXTO
            "pdf" -> Categoria.PDF
            in DOCUMENTOS -> Categoria.DOCUMENTO
            in HOJAS -> Categoria.HOJA
            in PRESENTACIONES -> Categoria.PRESENTACION
            in COMPRIMIDOS -> Categoria.COMPRIMIDO
            in PAQUETES -> Categoria.APK
            else -> Categoria.OTRO
        }

        fun desde(file: File): ArchivoItem {
            val esCarpeta = file.isDirectory
            return ArchivoItem(
                archivo = file,
                nombre = file.name,
                esCarpeta = esCarpeta,
                tamano = if (esCarpeta) 0 else file.length(),
                mime = if (esCarpeta) null else adivinarMime(file.name),
                ultimaModificacion = file.lastModified()
            )
        }

        fun adivinarMime(nombre: String): String {
            val ext = nombre.substringAfterLast('.', "").lowercase()
            MIMES[ext]?.let { return it }
            return when (categoriaDe(ext)) {
                Categoria.IMAGEN -> "image/*"
                Categoria.VIDEO -> "video/*"
                Categoria.AUDIO -> "audio/*"
                Categoria.TEXTO -> "text/plain"
                else -> "*/*"
            }
        }
    }
}
