package com.carpe.gestorarchivos.data

import android.net.Uri
import java.io.File

/** Lo que otra app le pide al gestor (elegir, guardar, recibir, abrir). */
class SolicitudExterna(
    val tipo: Tipo,
    /** ELEGIR_ARCHIVO: se permite marcar varios. */
    val multiple: Boolean = false,
    /** ELEGIR_ARCHIVO: tipos MIME aceptados (vacío = todos). */
    val mimes: List<String> = emptyList(),
    /** CREAR_DOCUMENTO / RECIBIR (texto): nombre propuesto. */
    val nombreSugerido: String? = null,
    /** RECIBIR: archivos recibidos. */
    val fuentes: List<Fuente> = emptyList(),
    /** RECIBIR: texto compartido sin archivo. */
    val textoRecibido: String? = null,
    /** ABRIR: archivo a abrir al mostrarse la pantalla. */
    val archivoAAbrir: File? = null,
    /** ELEGIR_ARCHIVO: ¿la otra app pidió también permiso de escritura sobre lo elegido? */
    val pideEscritura: Boolean = false
) {
    enum class Tipo { ELEGIR_ARCHIVO, ELEGIR_CARPETA, CREAR_DOCUMENTO, RECIBIR, ABRIR }

    class Fuente(val uri: Uri, val nombre: String)

    /** ¿Debe mostrarse este archivo cuando otra app pide un tipo concreto? Las carpetas siempre. */
    fun acepta(item: ArchivoItem): Boolean {
        if (item.esCarpeta) return true
        if (tipo == Tipo.ELEGIR_CARPETA) return false
        if (tipo != Tipo.ELEGIR_ARCHIVO || mimes.isEmpty()) return true
        val mime = ArchivoItem.adivinarMime(item.nombre)
        return mimes.any { patron -> coincide(patron, mime) }
    }

    companion object {
        fun coincide(patron: String, mime: String): Boolean {
            val p = patron.trim().lowercase()
            if (p.isEmpty() || p == "*/*" || p == "*") return true
            if (mime == "*/*") return false // tipo desconocido: no encaja con un patrón concreto
            if (p.endsWith("/*")) return mime.startsWith(p.dropLast(1))
            return p == mime.lowercase()
        }
    }
}
