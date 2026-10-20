package com.carpe.gestorarchivos.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Favoritos: carpetas y archivos. Se guardan por ruta.
 * Los favoritos antiguos (solo carpetas, sin el campo «carpeta») se leen como carpetas.
 */
class RepositorioFavoritos(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("gestor_prefs", Context.MODE_PRIVATE)

    data class Favorito(val ruta: String, val nombre: String, val esCarpeta: Boolean) {
        val archivo: File get() = File(ruta)
        val existe: Boolean get() = archivo.exists()

        /** Carpeta que contiene el favorito (o él mismo si es una carpeta raíz sin padre). */
        val carpetaPadre: File? get() = archivo.parentFile

        val categoria: Categoria
            get() = if (esCarpeta) Categoria.CARPETA else ArchivoItem.categoriaDe(nombre.substringAfterLast('.', "").lowercase())
    }

    fun listar(): List<Favorito> {
        val raw = prefs.getString("favoritos", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val ruta = o.optString("ruta", "")
                if (ruta.isEmpty()) return@mapNotNull null
                Favorito(ruta, o.optString("nombre", File(ruta).name), o.optBoolean("carpeta", true))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun rutas(): Set<String> = listar().map { it.ruta }.toSet()

    fun agregar(ruta: String, nombre: String, esCarpeta: Boolean): Boolean {
        val actual = listar().toMutableList()
        if (actual.any { it.ruta == ruta }) return false
        actual.add(Favorito(ruta, nombre, esCarpeta))
        guardar(actual)
        return true
    }

    fun agregar(f: File): Boolean = agregar(f.absolutePath, f.name.ifEmpty { f.absolutePath }, f.isDirectory)

    fun quitar(ruta: String) {
        guardar(listar().filterNot { it.ruta == ruta })
    }

    fun esFavorito(ruta: String): Boolean = listar().any { it.ruta == ruta }

    /** Devuelve true si tras la llamada es favorito. */
    fun alternar(f: File): Boolean = if (esFavorito(f.absolutePath)) {
        quitar(f.absolutePath); false
    } else {
        agregar(f); true
    }

    /**
     * Mantiene los favoritos al renombrar o mover: actualiza la ruta del elemento
     * y la de todo lo que haya dentro si era una carpeta.
     */
    fun actualizarRuta(antigua: String, nueva: String) {
        if (antigua == nueva) return
        val actual = listar()
        var cambio = false
        val nuevaLista = actual.map {
            when {
                it.ruta == antigua -> {
                    cambio = true
                    it.copy(ruta = nueva, nombre = File(nueva).name.ifEmpty { it.nombre })
                }
                it.ruta.startsWith(antigua + File.separator) -> {
                    cambio = true
                    it.copy(ruta = nueva + it.ruta.substring(antigua.length))
                }
                else -> it
            }
        }
        if (cambio) guardar(nuevaLista)
    }

    /** Elimina los favoritos cuyo archivo ya no existe. Devuelve cuántos quitó. */
    fun limpiarInexistentes(): Int {
        val actual = listar()
        val vivos = actual.filter { it.existe }
        if (vivos.size != actual.size) guardar(vivos)
        return actual.size - vivos.size
    }

    private fun guardar(lista: List<Favorito>) {
        val arr = JSONArray()
        lista.forEach {
            arr.put(JSONObject().apply {
                put("ruta", it.ruta)
                put("nombre", it.nombre)
                put("carpeta", it.esCarpeta)
            })
        }
        prefs.edit().putString("favoritos", arr.toString()).apply()
    }
}
