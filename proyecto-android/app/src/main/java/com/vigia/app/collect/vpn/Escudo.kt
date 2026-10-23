package com.vigia.app.collect.vpn

import com.vigia.app.store.BloqueoEntity

/**
 * Estado en memoria del Escudo: lo que está bloqueado ahora mismo. Se actualiza mientras hay una sesión con
 * captura DNS (en vivo, cada vez que el usuario añade o quita algo). Los dominios bloqueados incluyen sus
 * subdominios. El Escudo solo actúa mientras la VPN de Vigía está activa: no deja nada permanente.
 */
object Escudo {
    @Volatile
    var dominios: Set<String> = emptySet()
        private set

    @Volatile
    var ips: Set<Int> = emptySet()
        private set

    val hayDominios: Boolean get() = dominios.isNotEmpty()

    fun actualizar(lista: List<BloqueoEntity>) {
        val d = HashSet<String>()
        val i = HashSet<Int>()
        for (b in lista) {
            if (b.tipo == "dominio") {
                d.add(b.valor)
            } else if (b.tipo == "ip") {
                val ip = parseIp(b.valor)
                if (ip != null) i.add(ip)
            }
        }
        dominios = d
        ips = i
    }

    fun ipBloqueada(ip: Int): Boolean = ips.contains(ip)

    fun dominioBloqueado(nombre: String): Boolean {
        val bloqueados = dominios
        if (bloqueados.isEmpty()) return false
        var actual = nombre.lowercase()
        while (true) {
            if (bloqueados.contains(actual)) return true
            val punto = actual.indexOf('.')
            if (punto < 0) return false
            actual = actual.substring(punto + 1)
            if (!actual.contains('.')) return bloqueados.contains(actual)
        }
    }

    /** Limpia lo que escribe el usuario: quita http://, rutas, puertos y mayúsculas. Devuelve null si no es un dominio válido. */
    fun normalizarDominio(entrada: String): String? {
        var t = entrada.trim().lowercase()
        if (t.startsWith("http://")) t = t.substring(7)
        if (t.startsWith("https://")) t = t.substring(8)
        val barra = t.indexOf('/')
        if (barra >= 0) t = t.substring(0, barra)
        val dosPuntos = t.indexOf(':')
        if (dosPuntos >= 0) t = t.substring(0, dosPuntos)
        t = t.trim('.')
        if (t.length < 3 || t.length > 253 || !t.contains('.')) return null
        for (c in t) {
            val valido = (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_'
            if (!valido) return null
        }
        if (parseIp(t) != null) return null
        return t
    }

    fun parseIp(entrada: String): Int? {
        val partes = entrada.trim().split('.')
        if (partes.size != 4) return null
        var resultado = 0
        for (p in partes) {
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            resultado = (resultado shl 8) or n
        }
        return resultado
    }
}
