package com.carpe.gestorarchivos.data

import android.content.Context
import android.net.Uri

class RepositorioRecientes(context: Context) {

    private val prefs = context.getSharedPreferences("gestor_prefs", Context.MODE_PRIVATE)

    fun guardarUltimaCarpeta(uri: Uri) {
        prefs.edit().putString("ultima_carpeta", uri.toString()).apply()
    }

    fun leerUltimaCarpeta(): Uri? {
        val s = prefs.getString("ultima_carpeta", null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }
}