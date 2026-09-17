package com.tunombre.gestorarchivos.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RepositorioFavoritos(context: Context) {

    private val prefs = context.getSharedPreferences("gestor_prefs", Context.MODE_PRIVATE)

    data class Favorito(val ruta: String, val nombre: String)

    fun listar(): List<Favorito> {
        val raw = prefs.getString("favoritos", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val ruta = o.optString("ruta", "")
                if (ruta.isEmpty()) return@mapNotNull null
                Favorito(ruta, o.optString("nombre", "Carpeta"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun agregar(ruta: String, nombre: String): Boolean {
        val actual = listar().toMutableList()
        if (actual.any { it.ruta == ruta }) return false
        actual.add(Favorito(ruta, nombre))
        guardar(actual)
        return true
    }

    fun quitar(ruta: String) {
        val actual = listar().filterNot { it.ruta == ruta }
        guardar(actual)
    }

    fun esFavorito(ruta: String): Boolean = listar().any { it.ruta == ruta }

    fun alternar(ruta: String, nombre: String): Boolean {
        return if (esFavorito(ruta)) {
            quitar(ruta); false
        } else {
            agregar(ruta, nombre); true
        }
    }

    private fun guardar(lista: List<Favorito>) {
        val arr = JSONArray()
        lista.forEach {
            arr.put(JSONObject().apply {
                put("ruta", it.ruta)
                put("nombre", it.nombre)
            })
        }
        prefs.edit().putString("favoritos", arr.toString()).apply()
    }
}