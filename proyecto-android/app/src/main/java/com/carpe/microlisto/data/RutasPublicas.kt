package com.carpe.microlisto.data

import android.os.Environment
import java.io.File

/**
 * Gestión de la carpeta pública /storage/emulated/0/Microlisto/.
 *
 * A diferencia de filesDir (carpeta privada de la app), esta carpeta NO se
 * borra al desinstalar la app. Sirve para guardar cosas que queremos que
 * sobrevivan entre instalaciones: el modelo Whisper (620 MB, costoso de
 * descargar) y un backup de la base de datos (para no perder el historial).
 */
object RutasPublicas {

    fun raiz(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Microlisto")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun dirWhisper(): File {
        val dir = File(raiz(), "whisper")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun archivoModeloWhisper(nombre: String): File {
        return File(dirWhisper(), nombre)
    }

    fun archivoBackupBD(): File {
        return File(raiz(), "microlisto.db")
    }

    fun archivoExportTemporal(nombre: String): File {
        val dir = File(raiz(), "export")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, nombre)
    }

    /**
     * Comprueba si el almacenamiento externo está accesible.
     */
    fun hayAcceso(): Boolean {
        return try {
            raiz().canWrite()
        } catch (_: Exception) {
            false
        }
    }
}