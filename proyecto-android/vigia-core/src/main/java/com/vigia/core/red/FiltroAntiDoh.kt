package com.vigia.core.red

/**
 * Envuelve un filtro y le añade el bloqueo de resolutores DNS cifrados conocidos (DoH/DoT). Una app con DNS cifrado
 * propio resuelve por su cuenta y se salta el filtrado por nombre; si no puede llegar a su resolutor, vuelve al DNS
 * del sistema, que sí pasa por el túnel. La lista es la de NetLens 2.4.
 *
 * Se aplica también a [dominioBloqueadoEnDns]: así sigue funcionando aunque el filtro envuelto (modo MitM) deje
 * pasar todo en el DNS.
 */
class FiltroAntiDoh(private val base: FiltroTrafico) : FiltroTrafico {

    override val hayDominios: Boolean get() = true

    override fun dominioBloqueado(nombre: String): Boolean = esResolutorCifrado(nombre) || base.dominioBloqueado(nombre)

    override fun ipBloqueada(ip: Int): Boolean = base.ipBloqueada(ip)

    override fun dominioBloqueadoEnDns(nombre: String): Boolean =
        esResolutorCifrado(nombre) || base.dominioBloqueadoEnDns(nombre)

    companion object {
        private val RESOLUTORES = setOf(
            "dns.google", "dns.google.com", "cloudflare-dns.com", "one.one.one.one",
            "chrome.cloudflare-dns.com", "security.cloudflare-dns.com", "mozilla.cloudflare-dns.com",
            "dns.quad9.net", "dns9.quad9.net", "dns11.quad9.net", "dns.adguard.com", "dns.adguard-dns.com",
            "doh.opendns.com", "doh.familyshield.opendns.com", "dns.sb", "doh.dns.sb",
            "doh.mullvad.net", "doh.cleanbrowsing.org", "dns.surfshark.com", "dnsfast.doh.surfshark.com"
        )

        /** Verdadero si [nombre] (minúsculas) es un resolutor DoH/DoT conocido o un subdominio suyo. */
        fun esResolutorCifrado(nombre: String): Boolean {
            var actual = nombre
            while (true) {
                if (RESOLUTORES.contains(actual)) return true
                val punto = actual.indexOf('.')
                if (punto < 0) return false
                actual = actual.substring(punto + 1)
            }
        }
    }
}
