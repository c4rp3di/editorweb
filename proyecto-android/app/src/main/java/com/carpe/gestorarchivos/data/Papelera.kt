package com.carpe.gestorarchivos.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Papelera(context: Context) {

    private val contexto = context.applicationContext
    private val prefs = contexto.getSharedPreferences("gestor_papelera", Context.MODE_PRIVATE)
    private val lock = Any()

    data class Entrada(
        val id: String,
        val rutaOriginal: String,
        val nombre: String,
        val fecha: Long,
        val esCarpeta: Boolean,
        val rutaPapelera: String
    )

    companion object {
        const val NOMBRE = ".GestorPapelera"
        fun esRutaPapelera(f: File): Boolean = f.absolutePath.contains("/$NOMBRE/") || f.name == NOMBRE
    }

    private fun carpetaPapeleraDe(archivo: File): File =
        File(Almacenamientos.raizDe(contexto, archivo), NOMBRE)

    private fun leer(): MutableList<Entrada> = synchronized(lock) {
        val raw = prefs.getString("indice", null) ?: return mutableListOf()
        try {
            val arr = JSONArray(raw)
            val lista = mutableListOf<Entrada>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lista.add(
                    Entrada(
                        o.getString("id"), o.getString("original"), o.getString("nombre"),
                        o.getLong("fecha"), o.getBoolean("carpeta"), o.getString("papelera")
                    )
                )
            }
            lista
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun guardar(lista: List<Entrada>) = synchronized(lock) {
        val arr = JSONArray()
        lista.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("original", it.rutaOriginal); put("nombre", it.nombre)
                put("fecha", it.fecha); put("carpeta", it.esCarpeta); put("papelera", it.rutaPapelera)
            })
        }
        prefs.edit().putString("indice", arr.toString()).apply()
    }

    /** Mueve a la papelera. Si el archivo ya está dentro de ella, lo borra definitivamente. */
    fun enviar(archivo: File): Boolean {
        if (esRutaPapelera(archivo)) return GestorArchivos.borrarDefinitivo(archivo)
        val dir = carpetaPapeleraDe(archivo)
        if (!dir.exists() && !dir.mkdirs()) return false
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val destino = File(dir, id)
        val esCarpeta = archivo.isDirectory
        if (!GestorArchivos.moverComo(archivo, destino)) return false
        synchronized(lock) {
            val lista = leer()
            lista.add(Entrada(id, archivo.absolutePath, archivo.name, System.currentTimeMillis(), esCarpeta, destino.absolutePath))
            guardar(lista)
        }
        return true
    }

    /** Entradas que siguen existiendo, de la más reciente a la más antigua. */
    fun listar(): List<Entrada> = synchronized(lock) {
        val lista = leer()
        val vivas = lista.filter { File(it.rutaPapelera).exists() }
        if (vivas.size != lista.size) guardar(vivas)
        vivas.sortedByDescending { it.fecha }
    }

    fun restaurar(e: Entrada): Boolean {
        val origen = File(e.rutaPapelera)
        if (!origen.exists()) { quitar(e.id); return false }
        val original = File(e.rutaOriginal)
        val padre = original.parentFile ?: return false
        if (!padre.exists() && !padre.mkdirs()) return false
        val destino = GestorArchivos.nombreLibre(padre, original.name)
        return if (GestorArchivos.moverComo(origen, destino)) { quitar(e.id); true } else false
    }

    fun eliminarDefinitivo(e: Entrada): Boolean {
        val f = File(e.rutaPapelera)
        val ok = !f.exists() || GestorArchivos.borrarDefinitivo(f)
        if (ok) quitar(e.id)
        return ok
    }

    fun vaciar(): Int {
        var n = 0
        for (e in listar()) if (eliminarDefinitivo(e)) n++
        return n
    }

    fun purgarAntiguos(dias: Int) {
        val limite = System.currentTimeMillis() - dias * 86_400_000L
        for (e in listar()) if (e.fecha < limite) eliminarDefinitivo(e)
    }

    private fun quitar(id: String) = synchronized(lock) {
        guardar(leer().filterNot { it.id == id })
    }
}
