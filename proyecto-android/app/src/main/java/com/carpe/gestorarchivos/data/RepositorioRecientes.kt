package com.carpe.gestorarchivos.data

import android.content.Context
import java.io.File

class RepositorioRecientes(context: Context) {

    private val prefs = context.getSharedPreferences("gestor_prefs", Context.MODE_PRIVATE)

    fun guardarUltimaCarpeta(carpeta: File) {
        prefs.edit().putString("ultima_carpeta", carpeta.absolutePath).apply()
    }

    fun leerUltimaCarpeta(): File? {
        val ruta = prefs.getString("ultima_carpeta", null) ?: return null
        return runCatching { File(ruta) }.getOrNull()
    }
}