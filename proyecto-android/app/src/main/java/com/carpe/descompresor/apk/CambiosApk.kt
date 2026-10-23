package com.carpe.descompresor.apk

import java.io.File

/** Cambios pendientes sobre el APK abierto (se aplican al reempaquetar). */
class CambiosApk {
    val reemplazos = LinkedHashMap<String, File>()
    val eliminados = LinkedHashSet<String>()

    fun total(): Int = reemplazos.size + eliminados.size
    fun vacio(): Boolean = total() == 0

    fun limpiar() {
        reemplazos.values.forEach { it.delete() }
        reemplazos.clear()
        eliminados.clear()
    }
}
