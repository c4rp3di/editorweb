package com.vigia.adblock

import android.content.Context
import com.vigia.mitm.PoliticaMitm
import java.util.concurrent.ConcurrentHashMap

/**
 * Lista blanca automática del MitM. Se consulta en cada conexión 443 (hilo del túnel), así que vive en memoria y
 * solo se escribe en disco cuando cambia.
 */
class PoliticaAdBlock(contexto: Context) : PoliticaMitm {
    private val c = contexto.applicationContext
    private val exentas: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().also { it.addAll(Ajustes.mitmExentas(c)) }

    private fun clave(app: String, ip: String) = if (app == "desconocida" || app.isEmpty()) "ip:" + ip else app

    override fun intermediar(app: String, ip: String): Boolean = !exentas.contains(clave(app, ip))

    override fun alFallarHandshake(app: String, ip: String, host: String, motivo: String) {
        // El cliente cerró esta conexión (no se puede continuar un TLS ya rechazado); la siguiente, el reintento de la
        // app, irá directa al servidor real porque intermediar() ya devuelve falso.
        if (exentas.add(clave(app, ip))) Ajustes.setMitmExentas(c, HashSet(exentas))
    }

    fun lista(): Set<String> = HashSet(exentas)

    fun limpiar() {
        exentas.clear()
        Ajustes.setMitmExentas(c, emptySet())
    }

    fun quitar(clave: String) {
        if (exentas.remove(clave)) Ajustes.setMitmExentas(c, HashSet(exentas))
    }
}
