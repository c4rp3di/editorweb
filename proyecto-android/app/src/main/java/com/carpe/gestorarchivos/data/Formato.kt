package com.carpe.gestorarchivos.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formato {
    fun tamano(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    fun fecha(ms: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))

    fun fechaCorta(ms: Long): String =
        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(ms))
}
