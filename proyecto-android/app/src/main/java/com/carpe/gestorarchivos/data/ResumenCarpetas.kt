package com.carpe.gestorarchivos.data

import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/** Calcula en segundo plano cuántos archivos/carpetas (de forma recursiva) y cuánto ocupa una carpeta. */
object ResumenCarpetas {

    class Resumen(
        val archivos: Int,
        val carpetas: Int,
        val bytes: Long,
        val incompleto: Boolean,
        val sinAcceso: Boolean
    )

    private class Entrada(val resumen: Resumen, val modificada: Long, val creada: Long)

    private const val VALIDEZ_MS = 120_000L
    private const val MAX_ENTRADAS = 100_000
    private const val MAX_TIEMPO_MS = 4_000L

    private val cache = LruCache<String, Entrada>(500)
    private val ejecutor = Executors.newFixedThreadPool(2)
    private val principal = Handler(Looper.getMainLooper())

    fun enCache(f: File): Resumen? {
        val e = cache.get(f.absolutePath) ?: return null
        if (e.modificada != f.lastModified() || System.currentTimeMillis() - e.creada > VALIDEZ_MS) return null
        return e.resumen
    }

    /** Descarta lo calculado (llamar tras copiar, mover, borrar, extraer…). */
    fun invalidar() = cache.evictAll()

    fun pedir(f: File, vigente: () -> Boolean, alListo: (Resumen) -> Unit) {
        ejecutor.execute {
            if (!vigente()) return@execute
            val r = calcular(f)
            cache.put(f.absolutePath, Entrada(r, f.lastModified(), System.currentTimeMillis()))
            principal.post { alListo(r) }
        }
    }

    private fun calcular(raiz: File): Resumen {
        if (raiz.listFiles() == null) return Resumen(0, 0, 0, false, true)
        val limite = System.currentTimeMillis() + MAX_TIEMPO_MS
        val pila = ArrayDeque<File>()
        pila.add(raiz)
        var archivos = 0
        var carpetas = 0
        var bytes = 0L
        var incompleto = false
        while (pila.isNotEmpty()) {
            if (archivos + carpetas >= MAX_ENTRADAS || System.currentTimeMillis() > limite) {
                incompleto = true
                break
            }
            val dir = pila.removeLast()
            val hijos = dir.listFiles() ?: continue
            for (h in hijos) {
                if (h.isDirectory) {
                    carpetas++
                    pila.add(h)
                } else {
                    archivos++
                    bytes += h.length()
                }
            }
        }
        return Resumen(archivos, carpetas, bytes, incompleto, false)
    }

    fun descripcion(r: Resumen): String {
        if (r.sinAcceso) return "Sin acceso"
        if (r.archivos == 0 && r.carpetas == 0) return "Vacía"
        val mas = if (r.incompleto) "+" else ""
        val partes = ArrayList<String>()
        partes.add("${r.archivos}$mas ${if (r.archivos == 1) "archivo" else "archivos"}")
        if (r.carpetas > 0) partes.add("${r.carpetas}$mas ${if (r.carpetas == 1) "carpeta" else "carpetas"}")
        partes.add(Formato.tamano(r.bytes) + mas)
        return partes.joinToString(" · ")
    }
}
