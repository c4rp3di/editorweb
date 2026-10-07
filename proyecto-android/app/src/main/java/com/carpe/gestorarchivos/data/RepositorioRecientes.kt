package com.carpe.gestorarchivos.data

import android.content.Context
import org.json.JSONArray
import java.io.File

class RepositorioRecientes(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("gestor_prefs", Context.MODE_PRIVATE)

    fun guardarUltimaCarpeta(carpeta: File) {
        prefs.edit().putString("ultima_carpeta", carpeta.absolutePath).apply()
    }

    fun leerUltimaCarpeta(): File? {
        val ruta = prefs.getString("ultima_carpeta", null) ?: return null
        return runCatching { File(ruta) }.getOrNull()
    }

    // ---------- Archivos abiertos desde la app ----------

    fun registrarArchivo(archivo: File) {
        val lista = archivosRecientes().filter { it.absolutePath != archivo.absolutePath }.toMutableList()
        lista.add(0, archivo)
        guardarArchivos(lista.take(MAX_RECIENTES))
    }

    /** Más reciente primero. Puede incluir archivos que ya no existen; quien lo muestre debe filtrarlos. */
    fun archivosRecientes(): List<File> {
        val raw = prefs.getString("recientes_archivos", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { File(arr.getString(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun borrarArchivosRecientes() {
        prefs.edit().remove("recientes_archivos").apply()
    }

    private fun guardarArchivos(lista: List<File>) {
        val arr = JSONArray()
        lista.forEach { arr.put(it.absolutePath) }
        prefs.edit().putString("recientes_archivos", arr.toString()).apply()
    }

    companion object {
        private const val MAX_RECIENTES = 50
    }
}
