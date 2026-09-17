package com.carpe.gestorarchivos.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class RepositorioFavoritos(context: Context) {

    private val prefs = context.getSharedPreferences("gestor_prefs", Context.MODE_PRIVATE)

    data class Favorito(val uri: Uri, val nombre: String)

    fun listar(): List<Favorito> {
        val raw = prefs.getString("favoritos", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val uri = runCatching { Uri.parse(o.getString("uri")) }.getOrNull() ?: return@mapNotNull null
                Favorito(uri, o.optString("nombre", uri.lastPathSegment ?: "Carpeta"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun agregar(uri: Uri, nombre: String): Boolean {
        val actual = listar().toMutableList()
        if (actual.any { it.uri == uri }) return false
        actual.add(Favorito(uri, nombre))
        guardar(actual)
        return true
    }

    fun quitar(uri: Uri) {
        val actual = listar().filterNot { it.uri == uri }
        guardar(actual)
    }

    fun esFavorito(uri: Uri): Boolean = listar().any { it.uri == uri }

    fun alternar(uri: Uri, nombre: String): Boolean {
        return if (esFavorito(uri)) {
            quitar(uri); false
        } else {
            agregar(uri, nombre); true
        }
    }

    private fun guardar(lista: List<Favorito>) {
        val arr = JSONArray()
        lista.forEach {
            arr.put(JSONObject().apply {
                put("uri", it.uri.toString())
                put("nombre", it.nombre)
            })
        }
        prefs.edit().putString("favoritos", arr.toString()).apply()
    }
}