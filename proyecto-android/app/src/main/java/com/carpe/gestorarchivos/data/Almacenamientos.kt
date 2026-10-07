package com.carpe.gestorarchivos.data

import android.content.Context
import android.os.Environment
import java.io.File

object Almacenamientos {

    data class Volumen(val nombre: String, val raiz: File)

    fun volumenes(context: Context): List<Volumen> {
        val lista = mutableListOf<Volumen>()
        val interno = Environment.getExternalStorageDirectory()
        lista.add(Volumen("Almacenamiento interno", interno))
        val dirs = context.applicationContext.getExternalFilesDirs(null)
        for (d in dirs) {
            if (d == null) continue
            val ruta = d.absolutePath
            val idx = ruta.indexOf("/Android/data")
            if (idx <= 0) continue
            val raiz = File(ruta.substring(0, idx))
            if (lista.any { it.raiz.absolutePath == raiz.absolutePath }) continue
            lista.add(Volumen("Tarjeta SD / USB (${raiz.name})", raiz))
        }
        return lista
    }

    /** Raíz del volumen al que pertenece el archivo (o el almacenamiento interno si no se reconoce). */
    fun raizDe(context: Context, archivo: File): File {
        val ruta = archivo.absolutePath
        val candidatas = volumenes(context).map { it.raiz }
            .filter { ruta == it.absolutePath || ruta.startsWith(it.absolutePath + "/") }
        return candidatas.maxByOrNull { it.absolutePath.length } ?: Environment.getExternalStorageDirectory()
    }

    fun esRaiz(context: Context, carpeta: File): Boolean =
        volumenes(context).any { it.raiz.absolutePath == carpeta.absolutePath }

    fun nombreCorto(context: Context, raiz: File): String =
        if (raiz.absolutePath == Environment.getExternalStorageDirectory().absolutePath) "Interno" else "SD"
}
