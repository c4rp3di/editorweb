package com.carpe.gestorarchivos.data

import android.net.Uri

data class ArchivoItem(
    val nombre: String,
    val esCarpeta: Boolean,
    val uri: Uri,
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

    val clave: String get() = uri.toString()
}