package com.vigia.adblock

import com.vigia.core.red.FiltroTrafico
import com.vigia.mitm.EventosMitm
import com.vigia.mitm.FiltroContenido
import com.vigia.mitm.TipoRecurso

/**
 * Filtro en modo MitM. El DNS NO bloquea (si bloqueara, la petición no llegaría nunca al proxy y no habría 200 vacío);
 * el bloqueo lo hace el proxy por host + ruta. El resto del comportamiento (SNI de apps exentas) es el del 1.0.
 */
class FiltroProfundo(private val base: FiltroTrafico) : FiltroTrafico by base {
    override fun dominioBloqueadoEnDns(nombre: String): Boolean = false
}

/**
 * Reglas de ruta del usuario (de NetLens 2.6): una por línea, «dominio.com/ruta/» (con asterisco final opcional). El host incluye sus subdominios.
 * Sin «*» la ruta debe coincidir exacta; «*» al final = prefijo; «*» intermedio = comodín. La query (?…) no se evalúa.
 * Las líneas vacías o que empiezan por # se ignoran.
 */
class ReglasRuta private constructor(private val reglas: List<Regla>) {

    private class Regla(val host: String, val patron: String) {
        val regex: Regex? =
            if (patron.endsWith("*") || !patron.contains('*')) null
            else Regex(patron.split('*').joinToString(".*") { Regex.escape(it) })

        fun coincideRuta(ruta: String): Boolean {
            if (patron == "/") return true
            if (patron.endsWith("*")) return ruta.startsWith(patron.dropLast(1))
            val re = regex ?: return ruta == patron
            return re.matches(ruta)
        }
    }

    val cantidad: Int get() = reglas.size

    /** [host] en minúsculas y sin puerto; [ruta] con o sin query. */
    fun coincide(host: String, ruta: String): Boolean {
        if (reglas.isEmpty()) return false
        val r = ruta.substringBefore('?')
        for (regla in reglas) {
            if ((host == regla.host || host.endsWith("." + regla.host)) && regla.coincideRuta(r)) return true
        }
        return false
    }

    companion object {
        val VACIAS = ReglasRuta(emptyList())

        fun parsear(texto: String): ReglasRuta {
            val lista = ArrayList<Regla>()
            for (cruda in texto.split('\n')) {
                val linea = cruda.trim().lowercase()
                if (linea.isEmpty() || linea.startsWith("#")) continue
                val corte = linea.indexOf('/')
                if (corte <= 0) continue
                val host = linea.substring(0, corte).trimEnd('.')
                if (host.isEmpty() || !host.contains('.')) continue
                val patron = linea.substring(corte)
                lista.add(Regla(host, patron))
            }
            return if (lista.isEmpty()) VACIAS else ReglasRuta(lista)
        }
    }
}

class FiltroContenidoAdBlock(
    private val base: FiltroAdBlock,
    private val propias: ReglasRuta = ReglasRuta.VACIAS
) : FiltroContenido {

    override fun bloquear(host: String, ruta: String, tipo: TipoRecurso): Boolean {
        // Tus reglas mandan sobre todo lo demás (también sobre las navegaciones directas).
        if (propias.coincide(host, ruta)) return true
        // Una navegación directa a un dominio de anuncios no se vacía: el usuario la pidió.
        if (tipo != TipoRecurso.DOCUMENTO && base.dominioBloqueado(host)) return true
        val r = ruta.substringBefore('?').lowercase()
        return RUTAS.any { r.contains(it) }
    }

    override fun cssCosmetico(host: String): String? = CSS

    companion object {
        /** Rutas típicas de anuncios en dominios que mezclan contenido y publicidad. Conservadora a propósito. */
        private val RUTAS = listOf("/pagead/", "/adserver/", "/ads/banner", "/doubleclick/", "/adsbygoogle.js", "/prebid")

        private val SELECTORES = listOf(
            "ins.adsbygoogle", "[id^=\"google_ads_\"]", "[id^=\"div-gpt-ad\"]", "[class*=\"adsbygoogle\"]",
            "iframe[src*=\"doubleclick.net\"]", "iframe[src*=\"googlesyndication.com\"]", "iframe[src*=\"adservice.\"]",
            "[data-ad-slot]", "[data-ad-unit]", "[aria-label=\"Advertisement\"]", "[aria-label=\"Publicidad\"]",
            ".ad-slot", ".ad-container", ".ad-banner", ".advert", ".advertisement", ".publicidad", ".sponsored-ad",
            "#ad-banner", "#ads", "#adContainer"
        )

        // Sin comas fuera de los selectores y sin '<' para que sea seguro dentro de <style>.
        val CSS: String = SELECTORES.joinToString(",") + "{display:none!important;height:0!important;min-height:0!important;margin:0!important;padding:0!important}"
    }
}

/** Cuenta los bloqueos del MitM en las mismas estadísticas que el DNS. */
object EventosAdBlock : EventosMitm {
    override fun alBloquear(host: String, ruta: String, tipo: TipoRecurso) {
        Estadisticas.registrar(host)
        Laboratorio.nota("MITM " + tipo.name, "descifrado", host + ruta.substringBefore('?').take(80) + " → 200 vacío", true)
    }
}
