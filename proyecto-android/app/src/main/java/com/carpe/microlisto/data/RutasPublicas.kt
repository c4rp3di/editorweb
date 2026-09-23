package com.carpe.microlisto.data

import android.os.Environment
import java.io.File

object RutasPublicas {

    fun raiz(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Microlisto")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ==== Vosk ====
    fun dirVosk(): File {
        val dir = File(raiz(), "vosk")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun rutaModeloVoskDescomprimido(): File {
        return File(dirVosk(), "vosk-model-es-0.42")
    }

    // ==== Whisper ====
    fun dirWhisper(): File {
        val dir = File(raiz(), "whisper")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun archivoModeloWhisper(nombre: String): File {
        return File(dirWhisper(), nombre)
    }

    // ==== Backup BD ====
    fun archivoBackupBD(): File {
        return File(raiz(), "microlisto.db")
    }

    fun hayAcceso(): Boolean {
        return try {
            raiz().canWrite()
        } catch (_: Exception) {
            false
        }
    }
}